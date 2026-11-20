package dev.kiln.server.runtime.extension

import dev.kiln.protocol.ProtocolLimits
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ExtensionPackagerTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    private fun manifest(
        id: String = "demo",
        permissions: String = """["hud", "widgets", "channels"]""",
        extra: String = "",
    ) = """{"id":"$id","version":"1.0.0","name":"Demo","permissions":$permissions$extra}""".toByteArray()

    private fun files(manifest: ByteArray = manifest()): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "extension.json" to manifest,
        "main.lua" to "print('hi')".toByteArray(),
        "util.lua" to "return {}".toByteArray(),
        "textures/factory_icon.png" to png,
        "lang/de_de.json" to """{"a":"ä"}""".toByteArray(),
    )

    @Test
    fun `valid package is packed with canonical permissions`() {
        val pkg = ExtensionPackager.build("demo", files())
        assertEquals("demo", pkg.id)
        assertEquals("1.0.0", pkg.version)
        assertEquals("Demo", pkg.name)
        assertEquals("main.lua", pkg.main)
        assertEquals(listOf("widgets", "hud", "channels"), pkg.permissions)
        assertEquals(pkg.files.sorted(), pkg.files)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(pkg.zip), pkg.sha256)
        assertEquals(64, pkg.sha256Hex.length)
        assertEquals(pkg.zip.size, pkg.info.size)
    }

    @Test
    fun `zip is deterministic and has sorted root entries with a fixed timestamp`() {
        val a = ExtensionPackager.build("demo", files())
        val reversed = LinkedHashMap<String, ByteArray>()
        files().entries.reversed().forEach { reversed[it.key] = it.value.copyOf() }
        Thread.sleep(5)
        val b = ExtensionPackager.build("demo", reversed)
        assertArrayEquals(a.zip, b.zip)
        assertArrayEquals(a.sha256, b.sha256)

        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(a.zip)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                assertFalse(entry.isDirectory)
                assertEquals(LocalDateTime.of(1980, 1, 1, 0, 0), entry.timeLocal)
                assertArrayEquals(files().getValue(entry.name), zip.readAllBytes())
            }
        }
        assertEquals(listOf("extension.json", "lang/de_de.json", "main.lua", "textures/factory_icon.png", "util.lua"), names)

        // a content change changes the hash
        val changed = files().also { it["main.lua"] = "print('ho')".toByteArray() }
        assertFalse(ExtensionPackager.build("demo", changed).sha256.contentEquals(a.sha256))
    }

    @Test
    fun `custom main and missing optional fields`() {
        val f = files("""{"id":"demo","version":"2","name":"D","main":"util.lua"}""".toByteArray())
        val pkg = ExtensionPackager.build("demo", f)
        assertEquals("util.lua", pkg.main)
        assertEquals(emptyList<String>(), pkg.permissions)
        // unknown fields are ignored
        ExtensionPackager.build("demo", files(manifest(extra = ""","assets":["textures/*.png"]""")))
    }

    @Test
    fun `invalid manifests are rejected`() {
        fun rejects(f: Map<String, ByteArray>, id: String = "demo", contains: String) {
            val e = assertThrows<InvalidExtensionException> { ExtensionPackager.build(id, f) }
            assertTrue(e.message!!.contains(contains), "'${e.message}' should contain '$contains'")
        }
        rejects(files().also { it.remove("extension.json") }, contains = "missing")
        rejects(files(manifest(id = "other")), contains = "does not match")
        rejects(files(manifest(id = "Demo")), id = "Demo", contains = "Invalid extension id")
        rejects(files(manifest(id = "a".repeat(33))), id = "a".repeat(33), contains = "Invalid extension id")
        rejects(files(manifest(permissions = """["root"]""")), contains = "Unknown permission")
        rejects(files(manifest(permissions = """["hud","hud"]""")), contains = "Duplicate permission")
        rejects(files(manifest(permissions = """"hud"""")), contains = "must be an array")
        rejects(files("""{"id":"demo","name":"x"}""".toByteArray()), contains = "'version'")
        rejects(files("""{"id":"demo","version":"1","name":""}""".toByteArray()), contains = "'name'")
        rejects(files("""{"id":"demo","version":"1","name":"x","main":"nope.lua"}""".toByteArray()), contains = "does not exist")
        rejects(files("""{"id":"demo","version":"1","name":"x","main":"extension.json"}""".toByteArray()), contains = "not a .lua")
        rejects(files("not json {".toByteArray()), contains = "not valid JSON")
        rejects(files("[1]".toByteArray()), contains = "JSON object")
        // since v6 a package without main.lua (and without "main") is a resource-only package
        assertEquals(null, ExtensionPackager.build("demo", files().also { it.remove("main.lua") }).main)
    }

    @Test
    fun `invalid paths and contents are rejected`() {
        for (path in listOf("../evil.lua", "a/../b.lua", "/abs.lua", "a//b.lua", "./a.lua", "Main.lua", "a\\b.lua",
            "x.class", "readme.md", "noext", "textures/a b.png", "ä.lua", "a".repeat(ExtensionPackager.MAX_PATH_LENGTH) + ".lua")) {
            assertThrows<InvalidExtensionException>(path) { ExtensionPackager.build("demo", files().also { it[path] = "x".toByteArray() }) }
        }
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", files().also { it["textures/fake.png"] = "notpng".toByteArray() }) }
        assertThrows<InvalidExtensionException> {
            ExtensionPackager.build("demo", files().also { it["bad.lua"] = byteArrayOf(0xC3.toByte(), 0x28) })
        }
        val many = files().also { m -> repeat(ExtensionPackager.MAX_FILES) { m["f$it.lua"] = ByteArray(0) } }
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", many) }
    }

    @Test
    fun `size limits are enforced`() {
        // incompressible data above the zipped limit
        val noise = png + Random(7).nextBytes(ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES)
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", files().also { it["textures/noise.png"] = noise }) }
        // compressible data above the uncompressed limit
        val huge = files().also { it["huge.lua"] = ByteArray(ExtensionPackager.MAX_UNCOMPRESSED_BYTES + 1) { 'a'.code.toByte() } }
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", huge) }
    }

    @Test
    fun `chunking splits into full chunks and a rest`() {
        val size = ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES
        for (length in listOf(1, size - 1, size, size + 1, 3 * size + 17, ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES)) {
            val data = Random(length).nextBytes(length)
            val chunks = ExtensionChunker.split("demo", data)
            assertEquals((length + size - 1) / size, chunks.size)
            chunks.forEachIndexed { i, c ->
                assertEquals("demo", c.id)
                assertEquals(i, c.index)
                assertEquals(chunks.size, c.total)
                if (i < chunks.size - 1) assertEquals(size, c.data.size) else assertTrue(c.data.size in 1..size)
            }
            val joined = ByteArrayOutputStream().also { out -> chunks.forEach { out.write(it.data) } }.toByteArray()
            assertArrayEquals(data, joined)
        }
        assertThrows<IllegalArgumentException> { ExtensionChunker.split("demo", ByteArray(0)) }
    }

    @Test
    fun `files are read from directories and zips with or without id folder`(@TempDir dir: Path) {
        val src = dir.resolve("demo")
        for ((path, bytes) in files()) {
            val file = src.resolve(path)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
        val fromDir = ExtensionFiles.fromDirectory(src)
        assertEquals(files().keys, fromDir.keys)
        val expected = ExtensionPackager.build("demo", files())
        assertArrayEquals(expected.zip, ExtensionPackager.build("demo", fromDir).zip)

        for (prefix in listOf("", "demo/")) {
            val zipFile = dir.resolve("demo${prefix.length}.zip")
            ZipOutputStream(Files.newOutputStream(zipFile)).use { zip ->
                if (prefix.isNotEmpty()) zip.putNextEntry(ZipEntry(prefix))
                for ((path, bytes) in files()) {
                    zip.putNextEntry(ZipEntry(prefix + path))
                    zip.write(bytes)
                }
            }
            val fromZip = ExtensionFiles.fromZip(zipFile, "demo")
            assertEquals(files().keys, fromZip.keys)
            assertArrayEquals(expected.sha256, ExtensionPackager.build("demo", fromZip).sha256)
        }
    }

    private fun iconPng(width: Int, height: Int, padding: Int = 0) =
        png.copyOf(8) + byteArrayOf(0, 0, 0, 13) + "IHDR".toByteArray() +
            java.nio.ByteBuffer.allocate(8).putInt(width).putInt(height).array() + ByteArray(padding)

    @Test
    fun `optional icon is validated and carried in the manifest info`() {
        val ok = files(manifest(extra = ""","icon":"icon.png"""")).also { it["icon.png"] = iconPng(32, 32) }
        val pkg = ExtensionPackager.build("demo", ok)
        assertArrayEquals(ok.getValue("icon.png"), pkg.info.icon)
        assertEquals(null, ExtensionPackager.build("demo", files()).info.icon)

        fun bad(icon: String, content: ByteArray?) =
            files(manifest(extra = ""","icon":"$icon"""")).also { f -> content?.let { f["icon.png"] = it } }
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", bad("icon.png", null)) } // missing
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", bad("main.lua", iconPng(8, 8))) } // not .png
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", bad("icon.png", png)) } // no IHDR
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", bad("icon.png", iconPng(65, 32))) }
        assertThrows<InvalidExtensionException> {
            ExtensionPackager.build("demo", bad("icon.png", iconPng(16, 16, ExtensionPackager.MAX_ICON_BYTES)))
        }
    }

    // ---- v6: resource-pack trees ---------------------------------------------------------------------------

    private fun resourceFiles(vararg extra: Pair<String, String>): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "extension.json" to """{"id":"demo","version":"1","name":"Demo"}""".toByteArray(),
        "assets/demo/models/item/a.json" to """{"parent":"minecraft:item/generated","textures":{"layer0":"demo:item/a"}}""".toByteArray(),
    ).also { m -> extra.forEach { (k, v) -> m[k] = v.toByteArray() } }

    @Test
    fun `resource-only package without main gets the resources permission`() {
        val pkg = ExtensionPackager.build("demo", resourceFiles())
        assertEquals(null, pkg.main)
        assertTrue(pkg.hasAssets)
        assertEquals(listOf("resources"), pkg.permissions)
    }

    @Test
    fun `explicit main must exist`() {
        val f = resourceFiles()
        f["extension.json"] = """{"id":"demo","version":"1","name":"Demo","main":"start.lua"}""".toByteArray()
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", f) }
    }

    @Test
    fun `assets of a foreign namespace are rejected`() {
        val e = assertThrows<InvalidExtensionException> {
            ExtensionPackager.build("demo", resourceFiles("assets/minecraft/models/item/stick.json" to "{}"))
        }
        assertTrue(e.message!!.contains("assets/minecraft/models/item/stick.json"), e.message)
    }

    @Test
    fun `kiln references in asset json are rejected`() {
        assertThrows<InvalidExtensionException> {
            ExtensionPackager.build("demo", resourceFiles("assets/demo/font/x.json" to """{"providers":[{"type":"bitmap","file":"kiln:gui/x.png"}]}"""))
        }
    }

    @Test
    fun `foreign language keys are rejected`() {
        assertThrows<InvalidExtensionException> {
            ExtensionPackager.build("demo", resourceFiles("assets/demo/lang/en_us.json" to """{"gui.done":"Weg"}"""))
        }
        ExtensionPackager.build("demo", resourceFiles("assets/demo/lang/en_us.json" to """{"demo.title":"Demo"}"""))
    }

    @Test
    fun `ogg only below assets`() {
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", resourceFiles("sounds/a.ogg" to "OggS")) }
        ExtensionPackager.build("demo", resourceFiles("assets/demo/sounds/a.ogg" to "OggS"))
    }

    @Test
    fun `accepts up to the file limit`() {
        val f = resourceFiles()
        repeat(ProtocolLimits.MAX_EXTENSION_FILES - f.size) { f["assets/demo/textures/t$it.png.mcmeta"] = "{}".toByteArray() }
        assertEquals(ProtocolLimits.MAX_EXTENSION_FILES, ExtensionPackager.build("demo", f).files.size)
        f["assets/demo/one_more.txt"] = ByteArray(0)
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", f) }
    }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(33)
        b.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        b.putInt(13).put("IHDR".toByteArray()).putInt(width).putInt(height)
        return b.array()
    }

    @Test
    fun `oversized and headerless asset textures are rejected`() {
        val ok = resourceFiles().also { it["assets/demo/textures/item/a.png"] = pngHeader(4096, 16) }
        ExtensionPackager.build("demo", ok)
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", resourceFiles().also { it["assets/demo/textures/item/a.png"] = pngHeader(30000, 30000) }) }
        assertThrows<InvalidExtensionException> { ExtensionPackager.build("demo", resourceFiles().also { it["assets/demo/textures/item/a.png"] = png }) }
    }
}
