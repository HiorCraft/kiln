package dev.kiln.client.creative

import dev.kiln.protocol.message.CreativeTabData
import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreativeTabStateTest {
    private fun item(id: String) = ItemStackData(ResourceId.parse(id))
    private fun own(id: String, vararg items: String) = CreativeTabData(id, """{"text":"$id"}""", null, items.map(::item), null)

    @Test
    fun ownTabsFillSlotsInOrder() {
        val state = CreativeTabState()
        state.apply(true, listOf(own("a:one", "stone")))
        state.apply(false, listOf(own("a:two", "dirt", "sand")))
        assertEquals("a:one", state.slot(0)!!.id)
        assertEquals(listOf("minecraft:dirt", "minecraft:sand"), state.slot(1)!!.items.map { it.itemId.toString() })
        assertNull(state.slot(2))
    }

    @Test
    fun vanillaTargetsAreSeparated() {
        val state = CreativeTabState()
        state.apply(true, listOf(own("a:one", "stone"), CreativeTabData("minecraft:ingredients", "{}", null, listOf(item("diamond")), "minecraft:ingredients")))
        assertEquals(listOf("minecraft:diamond"), state.vanillaAdditions("minecraft:ingredients").map { it.itemId.toString() })
        assertTrue(state.vanillaAdditions("minecraft:combat").isEmpty())
        assertNull(state.slot(1))
    }

    @Test
    fun resetReplacesAndMoreThanEightOwnTabsAreIgnored() {
        val state = CreativeTabState()
        state.apply(true, List(10) { own("a:t$it", "stone") })
        assertEquals("a:t7", state.slot(7)!!.id)
        assertNull(state.slot(8))
        state.apply(true, listOf(own("b:x")))
        assertEquals("b:x", state.slot(0)!!.id)
        assertNull(state.slot(1))
    }

    @Test
    fun clearEmptiesEverything() {
        val state = CreativeTabState()
        state.apply(true, listOf(own("a:one", "stone")))
        val before = state.version
        state.clear()
        assertNull(state.slot(0))
        assertTrue(state.version > before)
    }
}
