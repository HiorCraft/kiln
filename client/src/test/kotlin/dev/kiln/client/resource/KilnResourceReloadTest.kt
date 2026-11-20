package dev.kiln.client.resource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A failed Kiln resource reload must reach the handshake (vanilla never completes the reload future then). */
class KilnResourceReloadTest {
    @Test
    fun failureOfAKilnReloadIsReportedOnceAndRemovesThePack() {
        val reload = KilnResourceReload()
        val results = ArrayList<String?>()
        var removed = 0
        reload.begin { results += it }
        assertTrue(reload.failed(IllegalStateException("atlas too large")) { removed++ })
        reload.completed() // a late completion changes nothing
        assertEquals(listOf<String?>("atlas too large"), results)
        assertEquals(1, removed)
    }

    @Test
    fun foreignFailuresAreLeftToVanilla() {
        val reload = KilnResourceReload()
        assertFalse(reload.failed(RuntimeException("player pack broken")) { error("must not remove") })
    }

    @Test
    fun successIsReportedOnce() {
        val reload = KilnResourceReload()
        val results = ArrayList<String?>()
        reload.begin { results += it }
        reload.completed()
        reload.completed()
        assertEquals(listOf<String?>(null), results)
        assertFalse(reload.failed(RuntimeException("later")) {})
    }
}
