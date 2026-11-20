package dev.kiln.server.runtime.item

import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CustomItemCatalogTest {
    private val extensions = mapOf(
        "kiln_content" to ContentExtension("KilnExample", setOf("assets/kiln_content/items/ruby.json", "assets/kiln_content/items/tools/wand.json")),
        "other" to ContentExtension("OtherPlugin", setOf("assets/other/items/ruby.json")),
    )

    private fun catalog() = CustomItemCatalog<String> { extensions[it] }

    @Test
    fun rejectsForeignNamespace() {
        val e = assertThrows<IllegalArgumentException> { catalog().add("KilnExample", Key.key("other", "ruby"), null, "x") }
        assertTrue(e.message!!.contains("other"), e.message)
        assertThrows<IllegalArgumentException> { catalog().add("KilnExample", Key.key("unknown", "ruby"), null, "x") }
    }

    @Test
    fun rejectsMissingItemDefinition() {
        val e = assertThrows<IllegalArgumentException> { catalog().add("KilnExample", Key.key("kiln_content", "emerald"), null, "x") }
        assertEquals(
            "Item model kiln_content:emerald not found in extension kiln_content (expected assets/kiln_content/items/emerald.json)",
            e.message,
        )
    }

    @Test
    fun acceptsExistingDefinition() {
        val c = catalog()
        c.add("KilnExample", Key.key("kiln_content", "wand"), Key.key("kiln_content", "tools/wand"), "wand")
        assertEquals("wand", c.get(Key.key("kiln_content", "wand")))
    }

    @Test
    fun rejectsDuplicateKey() {
        val c = catalog()
        c.add("KilnExample", Key.key("kiln_content", "ruby"), null, "a")
        assertThrows<IllegalStateException> { c.add("KilnExample", Key.key("kiln_content", "ruby"), null, "b") }
    }

    @Test
    fun modelDefaultsToKey() {
        assertEquals(Key.key("kiln_content", "ruby"), CustomItemCatalog.modelOf(Key.key("kiln_content", "ruby"), null))
    }

    @Test
    fun removesAllOfAPlugin() {
        val c = catalog()
        c.add("KilnExample", Key.key("kiln_content", "ruby"), null, "a")
        c.add("OtherPlugin", Key.key("other", "ruby"), null, "b")
        c.removeOwner("KilnExample")
        assertNull(c.get(Key.key("kiln_content", "ruby")))
        assertEquals("b", c.get(Key.key("other", "ruby")))
    }
}
