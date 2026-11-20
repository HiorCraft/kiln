package dev.kiln.server.runtime.enchantment

import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnchantmentHooksTest {
    @Test
    fun levelIsHighestAcrossSlots() {
        assertEquals(3, EnchantmentHooks.highestLevel(listOf(0, 3, 1)))
        assertEquals(0, EnchantmentHooks.highestLevel(emptyList()))
    }

    @Test
    fun missingExtensionProducesWarning() {
        val warnings = EnchantmentHooks.warnings(
            setOf(Key.key("kiln_content", "lifesteal"), Key.key("ghost", "curse")),
            setOf("kiln_content"),
        )
        assertEquals(listOf("Enchantment ghost:curse: no extension 'ghost' registered; its texts and assets will be missing"), warnings)
    }

    @Test
    fun handlerExceptionsAreContained() {
        val errors = ArrayList<String>()
        val calls = ArrayList<Int>()
        EnchantmentHooks.dispatch(
            listOf({ calls += 1 }, { throw IllegalStateException("boom") }, { calls += 3 }),
            onError = { errors += it.message!! },
        )
        assertEquals(listOf(1, 3), calls)
        assertTrue(errors == listOf("boom"))
    }
}
