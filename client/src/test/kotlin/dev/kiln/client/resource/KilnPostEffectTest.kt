package dev.kiln.client.resource

import net.minecraft.resources.Identifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A post effect that does not load must not be requested (vanilla would disable the server pack and disconnect). */
class KilnPostEffectTest {
    private val effect = Identifier.fromNamespaceAndPath("kiln_content", "ruby_vision")

    @AfterEach
    fun reset() = KilnPostEffect.request(null) { true }.let { }

    @Test
    fun brokenEffectIsNotRequested() {
        assertFalse(KilnPostEffect.request(effect) { false })
        assertNull(KilnPostEffect.current)
    }

    @Test
    fun workingEffectIsRequestedWhileProbing() {
        var probingDuringCheck = false
        assertTrue(KilnPostEffect.request(effect) { probingDuringCheck = KilnPostEffect.probing; true })
        assertTrue(probingDuringCheck)
        assertFalse(KilnPostEffect.probing)
        assertEquals(effect, KilnPostEffect.current)
    }

    @Test
    fun brokenEffectSwitchesAPreviousOneOff() {
        KilnPostEffect.request(effect) { true }
        KilnPostEffect.request(Identifier.fromNamespaceAndPath("kiln_content", "broken")) { false }
        assertNull(KilnPostEffect.current)
    }

    @Test
    fun probeExceptionsCountAsBroken() {
        assertFalse(KilnPostEffect.request(effect) { error("boom") })
        assertFalse(KilnPostEffect.probing)
    }

    @Test
    fun laterLoadFailureSwitchesTheEffectOff() {
        KilnPostEffect.request(effect) { true }
        assertTrue(KilnPostEffect.beginLoad(effect))
        assertTrue(KilnPostEffect.probing)
        KilnPostEffect.endLoad(loaded = false)
        assertFalse(KilnPostEffect.probing)
        assertNull(KilnPostEffect.current)
    }

    @Test
    fun otherEffectsAreNotGuarded() {
        KilnPostEffect.request(effect) { true }
        assertFalse(KilnPostEffect.beginLoad(Identifier.fromNamespaceAndPath("minecraft", "creeper")))
        assertFalse(KilnPostEffect.probing)
    }
}
