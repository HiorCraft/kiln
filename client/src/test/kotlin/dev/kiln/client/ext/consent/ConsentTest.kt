package dev.kiln.client.ext.consent

import dev.kiln.client.KilnClientImpl
import dev.kiln.protocol.ExtensionPermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsentTest {
    @Test
    fun `exactly one answer counts`() {
        val events = ArrayList<String>()
        val decision = ConsentDecision({ events += "accept" }, { events += "deny" })
        decision.answer(true)
        decision.answer(false) // e.g. Escape after the click, or a second click
        decision.answer(true)
        assertEquals(listOf("accept"), events)
        assertTrue(decision.answered)

        val denied = ArrayList<String>()
        ConsentDecision({ denied += "accept" }, { denied += "deny" }).apply { answer(false); answer(true) }
        assertEquals(listOf("deny"), denied)
    }

    @Test
    fun `server key comes from the connection's server entry`() {
        // regression: in the configuration phase Minecraft.getCurrentServer() is still null ("unknown")
        assertEquals("localhost:25566", KilnClientImpl.serverKey("LocalHost:25566 ", false))
        assertEquals("play.example.net", KilnClientImpl.serverKey("play.example.net", false))
        assertEquals("singleplayer", KilnClientImpl.serverKey(null, true))
        assertEquals("unknown", KilnClientImpl.serverKey("  ", false))
    }

    @Test
    fun `the resources permission names shaders`() {
        val label = PermissionTexts.LABELS.getValue(dev.kiln.protocol.ExtensionPermissions.RESOURCES)
        org.junit.jupiter.api.Assertions.assertTrue(label.contains("Shader"), label)
    }
}
