package dev.kiln.client.keybind

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyConflictsTest {
    private val taken = listOf("key.keyboard.w", "key.mouse.left", "key.keyboard.unknown")

    @Test
    fun `a default key another keybind uses starts unbound`() {
        assertTrue(KeyConflicts.startsUnbound("key.keyboard.w", taken))
        assertTrue(KeyConflicts.startsUnbound("key.mouse.left", taken))
    }

    @Test
    fun `a free default key is used`() {
        assertFalse(KeyConflicts.startsUnbound("key.keyboard.g", taken))
        // unbound is never a conflict
        assertFalse(KeyConflicts.startsUnbound("key.keyboard.unknown", taken))
    }
}
