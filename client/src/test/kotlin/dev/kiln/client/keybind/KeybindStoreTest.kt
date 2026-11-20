package dev.kiln.client.keybind

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KeybindStoreTest {

    @Test
    fun `bindings round-trip per server and id, defaults are not stored`(@TempDir dir: Path) {
        val file = dir.resolve("config/kiln-keybinds.json")
        val store = KeybindStore(file)
        store.load()
        store.set("play.example.net", "example:open", "key.keyboard.k", "key.keyboard.g")
        store.set("play.example.net", "example:ping", "key.keyboard.h", "key.keyboard.h")
        store.set("other.net", "example:open", "key.mouse.middle", "key.keyboard.g")
        store.save()

        val reloaded = KeybindStore(file)
        reloaded.load()
        assertEquals("key.keyboard.k", reloaded.get("play.example.net", "example:open"))
        assertNull(reloaded.get("play.example.net", "example:ping"))
        assertEquals("key.mouse.middle", reloaded.get("other.net", "example:open"))
        assertNull(reloaded.get("unknown", "example:open"))

        // back to the default removes the entry
        reloaded.set("play.example.net", "example:open", "key.keyboard.g", "key.keyboard.g")
        reloaded.save()
        val third = KeybindStore(file)
        third.load()
        assertNull(third.get("play.example.net", "example:open"))
        assertFalse(Files.readString(file).contains("play.example.net"))
    }

    @Test
    fun `corrupt file is ignored`(@TempDir dir: Path) {
        val file = dir.resolve("kiln-keybinds.json")
        Files.writeString(file, "{ not json")
        val store = KeybindStore(file)
        store.load()
        assertNull(store.get("a", "b"))
    }

    @Test
    fun `edge tracker reports transitions only`() {
        val edges = KeyEdgeTracker()
        assertNull(edges.update("k", false))
        assertEquals(true, edges.update("k", true))
        assertNull(edges.update("k", true))
        assertEquals(false, edges.update("k", false))
        edges.update("k", true)
        edges.forget("k")
        assertEquals(true, edges.update("k", true))
    }
}
