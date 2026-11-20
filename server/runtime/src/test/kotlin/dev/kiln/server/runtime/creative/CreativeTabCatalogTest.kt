package dev.kiln.server.runtime.creative

import dev.kiln.server.runtime.item.ContentExtension
import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CreativeTabCatalogTest {
    private val extensions = mapOf("demo" to ContentExtension("Demo", emptySet()), "other" to ContentExtension("Other", emptySet()))

    private fun catalog() = CreativeTabCatalog<String> { extensions[it] }

    @Test
    fun ninthOwnTabIsRejected() {
        val c = catalog()
        repeat(8) { c.addTab("Demo", Key.key("demo", "t$it"), "T$it") { listOf("x") } }
        val e = assertThrows<IllegalStateException> { c.addTab("Demo", Key.key("demo", "t8"), "T8") { emptyList() } }
        assertTrue(e.message!!.contains("8"), e.message)
    }

    @Test
    fun foreignNamespaceRejected() {
        assertThrows<IllegalArgumentException> { catalog().addTab("Demo", Key.key("other", "t"), "T") { emptyList() } }
    }

    @Test
    fun entriesKeepOrderAndTarget() {
        val c = catalog()
        c.addTab("Demo", Key.key("demo", "b"), "B") { listOf("b1") }
        c.addToVanilla("Demo", Key.key("minecraft", "ingredients")) { listOf("ruby") }
        c.addTab("Demo", Key.key("demo", "a"), "A") { listOf("a1", "a2") }
        val entries = c.entries()
        assertEquals(listOf("demo:b", "minecraft:ingredients", "demo:a"), entries.map { it.id.asString() })
        assertEquals(listOf(null, "minecraft:ingredients", null), entries.map { it.target?.asString() })
        assertEquals(listOf("a1", "a2"), entries[2].items())
    }

    @Test
    fun pluginDisableRemovesTabs() {
        val c = catalog()
        c.addTab("Demo", Key.key("demo", "a"), "A") { emptyList() }
        c.addTab("Other", Key.key("other", "a"), "B") { emptyList() }
        c.removeOwner("Demo")
        assertEquals(listOf("other:a"), c.entries().map { it.id.asString() })
    }

    @Test
    fun changesBumpTheVersion() {
        val c = catalog()
        val before = c.version
        c.addTab("Demo", Key.key("demo", "a"), "A") { emptyList() }
        assertTrue(c.version > before)
    }
}
