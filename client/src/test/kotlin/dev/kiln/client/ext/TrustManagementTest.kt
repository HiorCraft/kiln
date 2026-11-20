package dev.kiln.client.ext

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class TrustManagementTest {
    private val factory = TrustStore.Grant("factory", setOf("widgets", "hud"))
    private val chat = TrustStore.Grant("chat", emptySet())

    private fun store(dir: Path): Pair<Path, TrustStore> {
        val file = dir.resolve("config/kiln-trust.json")
        val store = TrustStore(file)
        store.load()
        store.trust("play.example.net", listOf(factory, chat))
        store.trust("other.net", listOf(factory))
        store.save()
        return file to store
    }

    /** Writes [content] and moves the modification time on, so the change is visible even on coarse file systems. */
    private fun writeExternally(file: Path, content: String) {
        val before = if (Files.exists(file)) Files.getLastModifiedTime(file).toMillis() else 0L
        Files.writeString(file, content)
        Files.setLastModifiedTime(file, FileTime.fromMillis(before + 5_000))
    }

    // ---- TrustStore ------------------------------------------------------------------------------------------

    @Test
    fun `remove revokes one server and saves immediately`(@TempDir dir: Path) {
        val (file, store) = store(dir)
        assertTrue(store.remove("play.example.net"))
        assertFalse(store.remove("play.example.net"))
        assertFalse(store.isTrusted("play.example.net", listOf(factory)))
        assertTrue(store.isTrusted("other.net", listOf(factory)))

        val reloaded = TrustStore(file).apply { load() }
        assertEquals(setOf("other.net"), reloaded.servers().keys)
        assertFalse(reloaded.isTrusted("play.example.net", listOf(chat)))
    }

    @Test
    fun `removeAll revokes every server and saves immediately`(@TempDir dir: Path) {
        val (file, store) = store(dir)
        assertEquals(setOf("play.example.net", "other.net"), store.removeAll())
        assertTrue(store.servers().isEmpty())
        assertEquals(emptySet<String>(), store.removeAll())

        val reloaded = TrustStore(file).apply { load() }
        assertTrue(reloaded.servers().isEmpty())
        assertTrue(reloaded.isTrusted("other.net", emptyList()))
        assertFalse(reloaded.isTrusted("other.net", listOf(factory)))
    }

    @Test
    fun `servers is a snapshot`(@TempDir dir: Path) {
        val (_, store) = store(dir)
        val snapshot = store.servers()
        store.remove("other.net")
        assertEquals(setOf("play.example.net", "other.net"), snapshot.keys)
        assertEquals(setOf(factory, chat), snapshot["play.example.net"])
    }

    @Test
    fun `external deletion of the file takes effect without a restart`(@TempDir dir: Path) {
        val (file, store) = store(dir)
        assertTrue(store.isTrusted("play.example.net", listOf(factory)))
        Files.delete(file)
        assertFalse(store.isTrusted("play.example.net", listOf(factory)))
        assertTrue(store.servers().isEmpty())
    }

    @Test
    fun `external edit is reloaded and not overwritten by the next change`(@TempDir dir: Path) {
        val (file, store) = store(dir)
        val revision = store.revision
        writeExternally(file, """{ "version": 1, "servers": { "edited.net": [ { "id": "x", "permissions": ["toasts"] } ] } }""")
        assertTrue(store.isTrusted("edited.net", listOf(TrustStore.Grant("x", setOf("toasts")))))
        assertFalse(store.isTrusted("play.example.net", listOf(factory)))
        assertTrue(store.revision > revision)

        // a change after the external edit builds on it
        store.trust("new.net", listOf(chat))
        store.save()
        val reloaded = TrustStore(file).apply { load() }
        assertEquals(setOf("edited.net", "new.net"), reloaded.servers().keys)
    }

    @Test
    fun `unchanged file is not reloaded and own unsaved grants stay`(@TempDir dir: Path) {
        val (_, store) = store(dir)
        val revision = store.revision
        assertTrue(store.isTrusted("other.net", listOf(factory)))
        assertEquals(revision, store.revision)
        store.trust("unsaved.net", listOf(chat))
        assertTrue(store.isTrusted("unsaved.net", listOf(chat)))
    }

    @Test
    fun `corrupt external edit means nothing is trusted until fixed`(@TempDir dir: Path) {
        val (file, store) = store(dir)
        writeExternally(file, "{ broken")
        assertFalse(store.isTrusted("other.net", listOf(factory)))
        writeExternally(file, """{ "version": 1, "servers": { "other.net": [ { "id": "factory", "permissions": ["hud", "widgets"] } ] } }""")
        assertTrue(store.isTrusted("other.net", listOf(factory)))
    }

    @Test
    fun `a never loaded store reads the file on first use`(@TempDir dir: Path) {
        val (file, _) = store(dir)
        val fresh = TrustStore(file)
        assertTrue(fresh.isTrusted("other.net", listOf(factory)))
    }

    // ---- revoking ----------------------------------------------------------------------------------------------

    @Test
    fun `revoking the connected server disconnects, others do not`(@TempDir dir: Path) {
        val (_, store) = store(dir)
        val events = ArrayList<String>()
        var current: String? = "play.example.net"
        val revoker = TrustRevoker(store, { current }) { events += "disconnect:$it" }

        assertTrue(revoker.revoke("other.net"))
        assertEquals(emptyList<String>(), events)
        assertFalse(revoker.revoke("unknown.net"))
        assertTrue(revoker.revoke("play.example.net"))
        assertEquals(listOf("disconnect:play.example.net"), events)
        assertFalse(store.isTrusted("play.example.net", listOf(factory)))

        // not connected anywhere: nothing to disconnect
        store.trust("play.example.net", listOf(factory))
        current = null
        revoker.revokeAll()
        assertEquals(1, events.size)
    }

    @Test
    fun `revoking all disconnects only if the current server had consent`(@TempDir dir: Path) {
        val (_, store) = store(dir)
        val events = ArrayList<String>()
        var current: String? = "no-extensions.net" // kiln server without extensions: no stored consent
        val revoker = TrustRevoker(store, { current }) { events += "disconnect:$it" }
        assertEquals(setOf("play.example.net", "other.net"), revoker.revokeAll())
        assertEquals(emptyList<String>(), events)

        store.trust("other.net", listOf(factory))
        current = "other.net"
        revoker.revokeAll()
        assertEquals(listOf("disconnect:other.net"), events)
    }

    @Test
    fun `entries are sorted with their grants`() {
        val entries = TrustEntry.of(linkedMapOf("b.net" to setOf(factory, chat), "a.net" to setOf(chat)))
        assertEquals(listOf("a.net", "b.net"), entries.map { it.server })
        assertEquals(listOf("chat", "factory"), entries[1].grants.map { it.id })
        assertEquals(4 + 2 * TrustEntry.GRANT_HEIGHT, TrustEntry.detailHeight(2))
    }

    @Test
    fun `accordion supports per-entry detail heights`() {
        val heights = intArrayOf(10, 40)
        val list = ConsentListState(2, headerHeight = 20, gap = 0, detailHeightOf = { heights[it] })
        list.toggle(0)
        list.toggle(1)
        assertEquals(30, list.entryHeight(0))
        assertEquals(60, list.entryHeight(1))
        assertEquals(90, list.contentHeight)
    }

    // ---- confirmation ------------------------------------------------------------------------------------------

    @Test
    fun `first press arms, second press of the same target confirms`() {
        val confirm = ArmedConfirmation<RevokeTarget>(timeoutMillis = 1000)
        val a = RevokeTarget.Server("a.net")
        assertNull(confirm.armed(0))
        assertFalse(confirm.press(a, 0))
        assertTrue(confirm.isArmed(a, 500))
        assertTrue(confirm.press(a, 500))
        assertNull(confirm.armed(500)) // disarmed after confirming
        assertFalse(confirm.press(a, 600)) // starts over
    }

    @Test
    fun `another target re-arms instead of confirming`() {
        val confirm = ArmedConfirmation<RevokeTarget>(timeoutMillis = 1000)
        assertFalse(confirm.press(RevokeTarget.Server("a.net"), 0))
        assertFalse(confirm.press(RevokeTarget.All, 100))
        assertEquals(RevokeTarget.All, confirm.armed(100))
        assertFalse(confirm.press(RevokeTarget.Server("a.net"), 200))
        assertFalse(confirm.press(RevokeTarget.Server("b.net"), 300))
        assertTrue(confirm.press(RevokeTarget.Server("b.net"), 400))
    }

    @Test
    fun `armed target expires`() {
        val confirm = ArmedConfirmation<RevokeTarget>(timeoutMillis = 1000)
        assertFalse(confirm.press(RevokeTarget.Cache, 0))
        assertTrue(confirm.isArmed(RevokeTarget.Cache, 1000))
        assertFalse(confirm.isArmed(RevokeTarget.Cache, 1001))
        assertFalse(confirm.press(RevokeTarget.Cache, 1500)) // expired: arms again
        assertTrue(confirm.press(RevokeTarget.Cache, 1600))
        confirm.press(RevokeTarget.All, 2000)
        confirm.disarm()
        assertNull(confirm.armed(2000))
    }

    // ---- cache -------------------------------------------------------------------------------------------------

    @Test
    fun `cache size and clear`(@TempDir dir: Path) {
        val cacheDir = dir.resolve("cache")
        val cache = ExtensionCache(cacheDir)
        assertEquals(0, cache.sizeBytes())
        assertEquals(0, cache.clear())
        Files.createDirectories(cacheDir)
        Files.write(cacheDir.resolve("aa.zip"), ByteArray(100))
        Files.write(cacheDir.resolve("bb.zip.tmp"), ByteArray(20))
        Files.write(cacheDir.resolve("notes.txt"), ByteArray(7)) // not ours
        assertEquals(120, cache.sizeBytes())
        assertEquals(2, cache.clear())
        assertEquals(0, cache.sizeBytes())
        assertTrue(Files.exists(cacheDir.resolve("notes.txt")))
    }
}
