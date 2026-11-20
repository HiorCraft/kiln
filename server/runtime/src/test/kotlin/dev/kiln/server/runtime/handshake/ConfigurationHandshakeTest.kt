package dev.kiln.server.runtime.handshake

import dev.kiln.protocol.message.ClientboundMessage
import dev.kiln.protocol.message.ExtensionChunk
import dev.kiln.protocol.message.ExtensionManifest
import dev.kiln.protocol.message.ExtensionRequest
import dev.kiln.protocol.message.ExtensionsReady
import dev.kiln.protocol.message.Hello
import dev.kiln.protocol.message.KeyPressed
import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.message.Welcome
import dev.kiln.server.runtime.extension.ExtensionPackage
import dev.kiln.server.runtime.extension.ExtensionPackager
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class ConfigurationHandshakeTest {
    private val timeouts = HandshakeTimeouts(helloMillis = 150, channelMillis = 150, consentMillis = 600, extensionsMillis = 300, resourceReloadMillis = 600, pollMillis = 10)

    private fun pkg(id: String, noise: Int = 0): ExtensionPackage {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + Random(id.hashCode()).nextBytes(noise)
        return ExtensionPackager.build(id, mapOf(
            "extension.json" to """{"id":"$id","version":"1","name":"$id","permissions":["widgets"]}""".toByteArray(),
            "main.lua" to "-- $id".toByteArray(),
            "textures/noise.png" to png,
        ))
    }

    private val small = pkg("small")
    private val large = pkg("large", ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES + 1000)

    private fun FakeHandshakeClient.run(packages: List<ExtensionPackage> = listOf(small, large)) = run(packages, timeouts)

    @Test
    fun `dependency problems disconnect after the version check without a manifest`() {
        val client = FakeHandshakeClient()
        client.reply(Hello(PROTOCOL_VERSION, emptyList()))
        val result = ConfigurationHandshake(client, client.inbox, emptyList(), timeouts, dependencyProblems = listOf("Extension 'shop' needs 'ui_kit'")).run()
        assertInstanceOf(HandshakeResult.Kick::class.java, result)
        assertTrue((result as HandshakeResult.Kick).reason.contains("Extension 'shop' needs 'ui_kit'"), result.reason)
        assertTrue(client.sent.none { it is ExtensionManifest })
    }

    @Test
    fun `full download succeeds and chunks reassemble to the announced packages`() {
        val client = FakeHandshakeClient()
        client.reply(Hello(PROTOCOL_VERSION, emptyList()))
        assertEquals(HandshakeResult.Success, client.run())

        assertEquals(Welcome(PROTOCOL_VERSION), client.sent[0])
        val manifest = client.sent[1] as ExtensionManifest
        assertEquals(listOf("small", "large"), manifest.extensions.map { it.id })
        assertEquals(listOf("widgets"), manifest.extensions[0].permissions)
        val chunks = client.sent.drop(2).map { it as ExtensionChunk }
        assertEquals(listOf("small", "large", "large"), chunks.map { it.id })
        for (pkg in listOf(small, large)) {
            val data = ByteArrayOutputStream().also { out -> chunks.filter { it.id == pkg.id }.forEach { out.write(it.data) } }.toByteArray()
            assertArrayEquals(pkg.zip, data)
            assertArrayEquals(pkg.sha256, manifest.extensions.first { it.id == pkg.id }.sha256)
        }
    }

    @Test
    fun `cached packages are not streamed and an empty manifest works`() {
        val client = FakeHandshakeClient(onManifest = { ExtensionRequest(listOf("large")) })
        client.reply(Hello(PROTOCOL_VERSION, emptyList()))
        assertEquals(HandshakeResult.Success, client.run())
        assertTrue(client.sent.filterIsInstance<ExtensionChunk>().all { it.id == "large" })

        val empty = FakeHandshakeClient(onManifest = { ExtensionRequest(emptyList()) })
        empty.reply(Hello(PROTOCOL_VERSION, emptyList()))
        assertEquals(HandshakeResult.Success, empty.run(emptyList()))
        assertEquals(ExtensionManifest(emptyList()), empty.sent[1])
    }

    @Test
    fun `download limit kicks before any chunk is sent`() {
        val client = FakeHandshakeClient()
        client.reply(Hello(PROTOCOL_VERSION, emptyList()))
        var asked = -1L
        val result = ConfigurationHandshake(client, client.inbox, listOf(small, large), timeouts, allowDownload = { asked = it; false }).run()
        assertInstanceOf(HandshakeResult.Kick::class.java, result)
        assertEquals(small.zip.size.toLong() + large.zip.size, asked)
        assertTrue(client.sent.none { it is ExtensionChunk })
    }

    @Test
    fun `no hello means no runtime`() {
        val result = FakeHandshakeClient().run()
        assertEquals(KickMessages.NO_RUNTIME, (result as HandshakeResult.Kick).reason)
    }

    @Test
    fun `version mismatch kicks after telling the client the server version`() {
        val client = FakeHandshakeClient()
        client.reply(Hello(2, listOf("old_mod")))
        val result = client.run() as HandshakeResult.Kick
        assertEquals(KickMessages.versionMismatch(2), result.reason)
        assertTrue(result.reason.contains("v$PROTOCOL_VERSION") && result.reason.contains("v2"))
        assertEquals(listOf<ClientboundMessage>(Welcome(PROTOCOL_VERSION)), client.sent)
    }

    @Test
    fun `unregistered channel kicks`() {
        val client = FakeHandshakeClient(registered = false)
        client.reply(Hello(PROTOCOL_VERSION, emptyList()))
        assertEquals(KickMessages.CHANNEL_NOT_REGISTERED, (client.run() as HandshakeResult.Kick).reason)
        assertTrue(client.sent.isEmpty())
    }

    @Test
    fun `client error and refusal`() {
        val failing = FakeHandshakeClient(ready = ExtensionsReady(false, "main.lua:1: boom"))
        failing.reply(Hello(PROTOCOL_VERSION, emptyList()))
        val result = failing.run() as HandshakeResult.Kick
        assertTrue(result.reason.contains("main.lua:1: boom"))

        // consent declined: the client closes the connection
        val declining = FakeHandshakeClient(onManifest = { null })
        declining.reply(Hello(PROTOCOL_VERSION, emptyList()))
        Thread { Thread.sleep(50); declining.open = false }.start()
        assertEquals(HandshakeResult.Closed, declining.run())
    }

    @Test
    fun `nothing is streamed before the client consented and the consent time is separate`() {
        // the client sits in its consent screen longer than the download timeout before it requests the packages
        lateinit var client: FakeHandshakeClient
        var chunksBeforeRequest = -1
        client = FakeHandshakeClient(onManifest = { manifest ->
            Thread {
                Thread.sleep(timeouts.extensionsMillis + 100)
                chunksBeforeRequest = client.sent.count { it is ExtensionChunk }
                client.reply(ExtensionRequest(manifest.extensions.map { it.id }))
            }.start()
            null
        })
        client.reply(Hello(PROTOCOL_VERSION, emptyList()))
        val streamed = Thread {
            // answer ExtensionsReady once all chunks of both packages arrived
            while (client.sent.count { it is ExtensionChunk } < small.info.chunkCount + large.info.chunkCount) Thread.sleep(5)
            client.reply(ExtensionsReady(true))
        }
        streamed.start()
        assertEquals(HandshakeResult.Success, client.run())
        assertEquals(0, chunksBeforeRequest)
        assertEquals(listOf("small", "large", "large"), client.sent.filterIsInstance<ExtensionChunk>().map { it.id })
    }

    @Test
    fun `manifest carries the icon`() {
        val icon = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13) +
            "IHDR".toByteArray() + byteArrayOf(0, 0, 0, 16, 0, 0, 0, 16)
        val withIcon = ExtensionPackager.build("iconic", mapOf(
            "extension.json" to """{"id":"iconic","version":"1","name":"I","icon":"icon.png"}""".toByteArray(),
            "main.lua" to ByteArray(0),
            "icon.png" to icon,
        ))
        val client = FakeHandshakeClient()
        client.reply(Hello(PROTOCOL_VERSION, emptyList()))
        assertEquals(HandshakeResult.Success, client.run(listOf(withIcon)))
        assertArrayEquals(icon, (client.sent[1] as ExtensionManifest).extensions.single().icon)
    }

    @Test
    fun `missing answers time out`() {
        val noRequest = FakeHandshakeClient(onManifest = { null })
        noRequest.reply(Hello(PROTOCOL_VERSION, emptyList()))
        // no answer to the consent screen: its own message, not "not loaded in time"
        assertEquals(KickMessages.CONSENT_TIMEOUT, (noRequest.run() as HandshakeResult.Kick).reason)
        assertTrue(KickMessages.CONSENT_TIMEOUT.startsWith("Keine Antwort auf die Zustimmungsabfrage"))

        val noReady = FakeHandshakeClient(ready = null)
        noReady.reply(Hello(PROTOCOL_VERSION, emptyList()))
        assertEquals(KickMessages.TIMEOUT, (noReady.run() as HandshakeResult.Kick).reason)
    }

    @Test
    fun `protocol violations kick`() {
        val unknown = FakeHandshakeClient(onManifest = { ExtensionRequest(listOf("nope")) })
        unknown.reply(Hello(PROTOCOL_VERSION, emptyList()))
        assertTrue((unknown.run() as HandshakeResult.Kick).reason.contains("nope"))

        val wrongOrder = FakeHandshakeClient(onManifest = { ExtensionsReady(true) })
        wrongOrder.reply(Hello(PROTOCOL_VERSION, emptyList()))
        assertInstanceOf(HandshakeResult.Kick::class.java, wrongOrder.run())

        val playMessage = FakeHandshakeClient()
        playMessage.reply(KeyPressed("x"))
        assertInstanceOf(HandshakeResult.Kick::class.java, playMessage.run())

        val garbage = FakeHandshakeClient()
        garbage.inbox.offerBytes(byteArrayOf(99, 1, 2))
        assertTrue((garbage.run() as HandshakeResult.Kick).reason.startsWith("Ungültige"))

        val flood = FakeHandshakeClient()
        repeat(100) { flood.reply(Hello(PROTOCOL_VERSION, emptyList())) }
        assertTrue((flood.run() as HandshakeResult.Kick).reason.contains("zu viele"))
    }

    /** The client reloads its resources after loading packages with assets: that time comes on top of the download. */
    @Test
    fun `packages with assets get extra time for the resource reload`() {
        val resources = ExtensionPackager.build("res", mapOf(
            "extension.json" to """{"id":"res","version":"1","name":"res"}""".toByteArray(),
            "assets/res/models/item/a.json" to "{}".toByteArray(),
        ))
        fun slowClient(): FakeHandshakeClient {
            lateinit var client: FakeHandshakeClient
            client = FakeHandshakeClient(ready = null, onManifest = { manifest ->
                // download done at once, then a resource reload longer than extensionsMillis
                Thread {
                    Thread.sleep(timeouts.extensionsMillis + 200)
                    client.reply(ExtensionsReady(true))
                }.start()
                ExtensionRequest(manifest.extensions.map { it.id })
            })
            return client
        }
        val withAssets = slowClient()
        withAssets.reply(Hello(PROTOCOL_VERSION, emptyList()))
        assertEquals(HandshakeResult.Success, withAssets.run(listOf(resources)))

        val withoutAssets = slowClient()
        withoutAssets.reply(Hello(PROTOCOL_VERSION, emptyList()))
        assertEquals(KickMessages.TIMEOUT, (withoutAssets.run(listOf(small)) as HandshakeResult.Kick).reason)
    }

}
