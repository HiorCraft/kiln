package dev.kiln.client.entity

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KilnEntityTraitsTest {
    @Test
    fun traitsBeforeSpawnApply() {
        val traits = KilnEntityTraits()
        traits.set(42, solid = true) // arrives before the entity exists on the client
        assertTrue(traits.isSolid(42))
        assertFalse(traits.isSolid(7))
    }

    @Test
    fun removeAndClear() {
        val traits = KilnEntityTraits()
        traits.set(1, true)
        traits.set(2, true)
        traits.set(3, false)
        traits.remove(listOf(1))
        assertFalse(traits.isSolid(1))
        assertTrue(traits.isSolid(2))
        traits.clear()
        assertFalse(traits.isSolid(2))
    }
}
