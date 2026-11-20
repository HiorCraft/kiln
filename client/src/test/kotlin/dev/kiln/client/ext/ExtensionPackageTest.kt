package dev.kiln.client.ext

import dev.kiln.client.ext.handshake.ChunkAssembler
import dev.kiln.client.ext.handshake.ExtensionCache
import dev.kiln.client.ext.handshake.Sha256
import dev.kiln.client.ext.trust.TrustGrant
import dev.kiln.client.ext.trust.TrustStore
import dev.kiln.protocol.message.ExtensionChunk
import dev.kiln.protocol.message.ExtensionInfo
import dev.kiln.protocol.ProtocolLimits
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionPackageTest {
    private val manifest = """{"id":"factory","version":"1.0.0","name":"Factory","main":"main.lua","permissions":["widgets","hud"]}"""

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun valid(vararg extra: Pair<String, ByteArray>) =
        zip("extension.json" to manifest.toByteArray(), "main.lua" to "-- hi".toByteArray(), *extra)

    @Test
    fun `valid package is read`() {
        val pkg = ExtensionPackage.read(valid("textures/gauge_bg.png" to ByteArray(4), "lib/util.lua" to ByteArray(1)), "factory")
        assertEquals("factory", pkg.id)
        assertEquals("1.0.0", pkg.version)
        assertEquals(setOf("widgets", "hud"), pkg.permissions)
        assertEquals("main.lua", pkg.main)
        assertTrue(pkg.files.containsKey("textures/gauge_bg.png"))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "../evil.lua", "textures/../../evil.lua", "/abs.lua", "C:/x.lua", "a\\b.lua", "Upper.lua", "x.class", "x.lua.exe",
        "a//b.lua", "./x.lua", "x", ".lua", "textures/x.PNG", "space name.lua",
    ])
    fun `forbidden entry names are rejected (zip slip)`(name: String) {
        assertFalse(ExtensionPackage.isValidPath(name), name)
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(valid(name to ByteArray(1))) }
    }

    @Test
    fun `too many entries are rejected`() {
        val many = (0..ExtensionPackage.MAX_FILES).map { "f$it.lua" to ByteArray(0) }.toTypedArray()
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(valid(*many)) }
    }

    @Test
    fun `zip bomb stops at the unpacked limit`() {
        val big = ByteArray((ExtensionPackage.MAX_UNPACKED_BYTES + 1024 * 1024).toInt()) // compresses to a few KiB
        val zip = valid("big.json" to big)
        assertTrue(zip.size < ExtensionPackage.MAX_ZIP_BYTES)
        val e = assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(zip) }
        assertTrue(e.message!!.contains("unpacked"), e.message)
    }

    @Test
    fun `oversized zip is rejected`() {
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(ByteArray(ExtensionPackage.MAX_ZIP_BYTES + 1)) }
    }

    @Test
    fun `manifest is validated`() {
        fun with(json: String, vararg files: Pair<String, ByteArray>) = zip("extension.json" to json.toByteArray(), "main.lua" to ByteArray(0), *files)
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(zip("main.lua" to ByteArray(0))) }
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(with("[]")) }
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(with("""{"id":"Bad-Id","version":"1","name":"x"}""")) }
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(with("""{"id":"x","version":"1","name":"x","permissions":["root"]}""")) }
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(with("""{"id":"x","version":"1","name":"x","permissions":["hud","hud"]}""")) }
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(with("""{"id":"x","version":"1","name":"x","main":"other.lua"}""")) }
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(with("""{"id":"x","version":"","name":"x"}""")) }
        // id must match what the server announced
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(valid(), "other") }
        // defaults: main.lua, no permissions; unknown fields are ignored
        val pkg = ExtensionPackage.read(with("""{"id":"x","version":"1","name":"x","assets":["a"]}"""))
        assertEquals("main.lua", pkg.main)
        assertTrue(pkg.permissions.isEmpty())
    }

    @Test
    fun `corrupt data is rejected`() {
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read("not a zip".toByteArray()) }
    }

    // ---- download ---------------------------------------------------------------------------------------------

    private fun info(data: ByteArray, id: String = "factory") =
        ExtensionInfo(id, "1.0.0", "Factory", Sha256.of(data), data.size, listOf("widgets"))

    private fun chunks(info: ExtensionInfo, data: ByteArray): List<ExtensionChunk> {
        val size = ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES
        return (0 until info.chunkCount).map { i ->
            ExtensionChunk(info.id, i, info.chunkCount, data.copyOfRange(i * size, minOf(data.size, (i + 1) * size)))
        }
    }

    @Test
    fun `chunks are reassembled and verified`() {
        val data = ByteArray(ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES * 2 + 123) { (it * 31).toByte() }
        val info = info(data)
        val assembler = ChunkAssembler(info)
        val parts = chunks(info, data)
        assertNull(assembler.add(parts[0]))
        assertNull(assembler.add(parts[1]))
        assertArrayEquals(data, assembler.add(parts[2]))
        assertTrue(assembler.isComplete)
    }

    @Test
    fun `bad chunks are rejected`() {
        val data = ByteArray(ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES + 10) { it.toByte() }
        val info = info(data)
        val parts = chunks(info, data)
        assertThrows(ExtensionPackageException::class.java) { ChunkAssembler(info).add(parts[1]) } // out of order
        assertThrows(ExtensionPackageException::class.java) { ChunkAssembler(info).add(ExtensionChunk("other", 0, 2, parts[0].data)) }
        assertThrows(ExtensionPackageException::class.java) { ChunkAssembler(info).add(ExtensionChunk("factory", 0, 3, parts[0].data)) }
        assertThrows(ExtensionPackageException::class.java) { ChunkAssembler(info).add(ExtensionChunk("factory", 0, 2, ByteArray(5))) }
        // tampered content: SHA-256 mismatch
        val tampered = ChunkAssembler(info)
        tampered.add(parts[0])
        val last = parts[1].data.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val e = assertThrows(ExtensionPackageException::class.java) { tampered.add(ExtensionChunk("factory", 1, 2, last)) }
        assertTrue(e.message!!.contains("SHA-256"))
    }

    @Test
    fun `cache stores by hash and drops corrupt files`(@TempDir dir: Path) {
        val data = "zip".toByteArray()
        val info = info(data)
        val cache = ExtensionCache(dir)
        assertNull(cache.get(info))
        cache.put(info, data)
        assertTrue(Files.exists(dir.resolve(Sha256.hex(info.sha256) + ".zip")))
        assertArrayEquals(data, cache.get(info))
        Files.write(dir.resolve(Sha256.hex(info.sha256) + ".zip"), "zap".toByteArray())
        assertNull(cache.get(info))
        assertFalse(Files.exists(dir.resolve(Sha256.hex(info.sha256) + ".zip")))
    }

    @Test
    fun `the cache drops the least recently used packages above its size limit`(@TempDir dir: Path) {
        val cache = ExtensionCache(dir, maxBytes = 250)
        val a = ByteArray(100) { 1 }
        val b = ByteArray(100) { 2 }
        val c = ByteArray(100) { 3 }
        cache.put(info(a), a)
        Files.setLastModifiedTime(dir.resolve(Sha256.hex(info(a).sha256) + ".zip"), java.nio.file.attribute.FileTime.fromMillis(1_000))
        cache.put(info(b), b)
        Files.setLastModifiedTime(dir.resolve(Sha256.hex(info(b).sha256) + ".zip"), java.nio.file.attribute.FileTime.fromMillis(2_000))
        // a is used again: b is now the oldest
        assertArrayEquals(a, cache.get(info(a)))
        cache.put(info(c), c)
        assertArrayEquals(a, cache.get(info(a)))
        assertNull(cache.get(info(b)))
        assertArrayEquals(c, cache.get(info(c)))
        assertTrue(cache.sizeBytes() <= 250)
    }

    // ---- trust ------------------------------------------------------------------------------------------------

    @Test
    fun `trust is persisted per server and set of id and permissions`(@TempDir dir: Path) {
        val file = dir.resolve("config/kiln-trust.json")
        val store = TrustStore(file)
        store.load()
        val request = listOf(TrustGrant("factory", setOf("widgets", "hud")), TrustGrant("chat", emptySet()))
        assertFalse(store.isTrusted("play.example.net", request))
        assertTrue(store.isTrusted("play.example.net", emptyList()))
        store.trust("play.example.net", request)
        store.save()

        val reloaded = TrustStore(file)
        reloaded.load()
        assertTrue(reloaded.isTrusted("play.example.net", request))
        assertTrue(reloaded.isTrusted("play.example.net", request.take(1)))
        assertFalse(reloaded.isTrusted("other.net", request))
        // more permissions or a new extension ask again
        assertFalse(reloaded.isTrusted("play.example.net", listOf(TrustGrant("factory", setOf("widgets", "hud", "keybinds")))))
        assertFalse(reloaded.isTrusted("play.example.net", request + TrustGrant("new", emptySet())))
        // a re-grant replaces the old permission set of that id
        reloaded.trust("play.example.net", listOf(TrustGrant("factory", setOf("widgets"))))
        assertFalse(reloaded.isTrusted("play.example.net", request.take(1)))
    }

    @Test
    fun `corrupt trust file means nothing is trusted`(@TempDir dir: Path) {
        val file = dir.resolve("trust.json")
        Files.writeString(file, "{ broken")
        val store = TrustStore(file)
        store.load()
        assertFalse(store.isTrusted("a", listOf(TrustGrant("x", emptySet()))))
    }

    // ---- v6: resource-pack trees ---------------------------------------------------------------------------

    private fun resources(vararg extra: Pair<String, String>, permissions: String = """["resources"]"""): ByteArray = zip(
        "extension.json" to """{"id":"factory","version":"1","name":"Factory","permissions":$permissions}""".toByteArray(),
        "assets/factory/models/item/a.json" to """{"parent":"minecraft:item/generated"}""".toByteArray(),
        *extra.map { it.first to it.second.toByteArray() }.toTypedArray(),
    )

    @Test
    fun `resource-only package has no main`() {
        val pkg = ExtensionPackage.read(resources("assets/factory/sounds/a.ogg" to "OggS"))
        assertNull(pkg.main)
        assertTrue(pkg.assets.containsKey("factory/models/item/a.json"), pkg.assets.keys.toString())
        assertTrue(pkg.assets.containsKey("factory/sounds/a.ogg"))
    }

    @Test
    fun `assets of a foreign namespace are rejected`() {
        val e = assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(resources("assets/minecraft/textures/gui/x.png" to "x")) }
        assertTrue(e.message!!.contains("assets/minecraft/textures/gui/x.png"), e.message)
    }

    @Test
    fun `kiln references in asset json are rejected`() {
        assertThrows(ExtensionPackageException::class.java) {
            ExtensionPackage.read(resources("assets/factory/font/x.json" to """{"providers":[{"type":"bitmap","file":"kiln:gui/x.png"}]}"""))
        }
    }

    @Test
    fun `foreign language keys in assets are rejected`() {
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(resources("assets/factory/lang/en_us.json" to """{"gui.done":"x"}""")) }
        ExtensionPackage.read(resources("assets/factory/lang/en_us.json" to """{"factory.title":"x"}"""))
    }

    @Test
    fun `sounds are only allowed below assets`() {
        assertFalse(ExtensionPackage.isValidPath("sounds/a.ogg"))
        assertTrue(ExtensionPackage.isValidPath("assets/factory/sounds/a.ogg"))
        assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(resources("sounds/a.ogg" to "OggS")) }
    }

    @Test
    fun `assets need the resources permission`() {
        val e = assertThrows(ExtensionPackageException::class.java) { ExtensionPackage.read(resources(permissions = "[]")) }
        assertTrue(e.message!!.contains("resources"), e.message)
    }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(33)
        b.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        b.putInt(13).put("IHDR".toByteArray()).putInt(width).putInt(height)
        return b.array()
    }

    @Test
    fun `oversized and headerless asset textures are rejected`() {
        fun read(png: ByteArray) = ExtensionPackage.read(zip(
            "extension.json" to """{"id":"factory","version":"1","name":"Factory","permissions":["resources"]}""".toByteArray(),
            "assets/factory/textures/item/a.png" to png,
        ))
        read(pngHeader(4096, 16))
        val e = assertThrows(ExtensionPackageException::class.java) { read(pngHeader(30000, 30000)) }
        assertTrue(e.message!!.contains("30000x30000"), e.message)
        assertThrows(ExtensionPackageException::class.java) { read(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1)) }
    }
}
