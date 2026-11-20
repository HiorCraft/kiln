package dev.kiln.server.runtime.handshake

import dev.kiln.protocol.ExtensionChunk
import dev.kiln.protocol.ExtensionRequest
import dev.kiln.protocol.ExtensionsReady
import dev.kiln.protocol.Hello
import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.codec.MessageCodec
import dev.kiln.server.runtime.extension.ExtensionPackager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class SendThrottleTest {
    private val chunk = ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES.toLong()

    @Test
    fun `unlimited sends everything at once and adds no time`() {
        val t = SendThrottle.UNLIMITED
        assertTrue(t.unlimited)
        assertEquals(0, t.sendOffsetMillis(0))
        assertEquals(0, t.sendOffsetMillis(100 * chunk))
        assertEquals(60_000, t.downloadTimeoutMillis(60_000, 100 * chunk))
    }

    @Test
    fun `config value in KiB, zero and negative mean unlimited`() {
        assertSame(SendThrottle.UNLIMITED, SendThrottle.kibPerSecond(0))
        assertSame(SendThrottle.UNLIMITED, SendThrottle.kibPerSecond(-5))
        assertEquals(1024L * 1024, SendThrottle.kibPerSecond(1024).bytesPerSecond)
        assertThrows<IllegalArgumentException> { SendThrottle(-1) }
    }

    @Test
    fun `chunks are spaced by their size divided by the rate`() {
        val t = SendThrottle.kibPerSecond(1024) // 1 MiB/s: one 512 KiB chunk every 500 ms
        assertEquals(0, t.sendOffsetMillis(0))
        assertEquals(500, t.sendOffsetMillis(chunk))
        assertEquals(1_000, t.sendOffsetMillis(2 * chunk))
        assertEquals(15_000, t.transferMillis(15 * 1024 * 1024))
    }

    @Test
    fun `offsets round up and are monotonic`() {
        val t = SendThrottle(3_000)
        assertEquals(1, t.sendOffsetMillis(1))
        assertEquals(334, t.sendOffsetMillis(1_000))
        var last = -1L
        for (bytes in 0L..10_000L step 7) {
            val offset = t.sendOffsetMillis(bytes)
            assertTrue(offset >= last)
            last = offset
        }
    }

    @Test
    fun `download timeout grows with the throttled transfer time`() {
        val t = SendThrottle.kibPerSecond(64)
        // 4 MiB at 64 KiB/s = 64 s, plus the configured 60 s for verification and loading
        assertEquals(124_000, t.downloadTimeoutMillis(60_000, 4L * 1024 * 1024))
    }

    @Test
    fun `handshake paces chunks on its own thread and extends the timeout`() {
        var now = 0L
        val sleeps = ArrayList<Long>()
        val sendTimes = ArrayList<Long>()
        val inbox = HandshakeInbox()
        val zipSize = 3 * ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES + 100
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + Random(1).nextBytes(zipSize)
        val pkg = ExtensionPackager.build("big", mapOf(
            "extension.json" to """{"id":"big","version":"1","name":"Big"}""".toByteArray(),
            "main.lua" to "-- big".toByteArray(),
            "textures/noise.png" to png,
        ))
        val link = object : HandshakeLink {
            var chunks = 0
            override fun send(message: dev.kiln.protocol.ClientboundMessage) {
                when (message) {
                    is dev.kiln.protocol.ExtensionManifest ->
                        inbox.offerBytes(MessageCodec.encodeServerbound(ExtensionRequest(listOf("big"))))
                    is ExtensionChunk -> {
                        sendTimes += now
                        if (++chunks == message.total) {
                            // the client answers long after the base timeout (100 ms) but within base + transfer time
                            now += 80
                            inbox.offerBytes(MessageCodec.encodeServerbound(ExtensionsReady(true)))
                        }
                    }
                    else -> Unit
                }
            }

            override fun isOpen() = true
            override fun channelRegistered() = true
        }
        inbox.offerBytes(MessageCodec.encodeServerbound(Hello(PROTOCOL_VERSION, emptyList())))
        val throttle = SendThrottle(ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES.toLong() * 20) // 50 ms per chunk
        val timeouts = HandshakeTimeouts(extensionsMillis = 100, pollMillis = 20)
        val result = ConfigurationHandshake(link, inbox, listOf(pkg), timeouts, throttle, { now }, { sleeps += it; now += it }).run()

        assertEquals(HandshakeResult.Success, result)
        assertEquals(pkg.info.chunkCount, sendTimes.size)
        assertEquals(listOf(0L, 50L, 100L, 150L).take(sendTimes.size), sendTimes)
        assertTrue(sleeps.all { it in 1..20 }, "sleeps in poll-sized steps: $sleeps")
    }
}
