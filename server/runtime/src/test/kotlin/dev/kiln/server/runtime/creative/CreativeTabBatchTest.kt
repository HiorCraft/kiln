package dev.kiln.server.runtime.creative

import dev.kiln.protocol.message.CreativeTabData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreativeTabBatchTest {
    private fun tab(id: String) = dev.kiln.protocol.message.CreativeTabData(id, "{}", null, emptyList(), null)

    /** The reset must not depend on the first entry being sendable (an oversized one is skipped). */
    @org.junit.jupiter.api.Test
    fun resetComesFirstAndAlone() {
        val messages = CreativeTabRegistry.batch(listOf(tab("a:one"), tab("a:two")))
        org.junit.jupiter.api.Assertions.assertEquals(listOf(true, false, false), messages.map { it.reset })
        org.junit.jupiter.api.Assertions.assertTrue(messages.first().tabs.isEmpty())
        org.junit.jupiter.api.Assertions.assertEquals(listOf("a:one", "a:two"), messages.drop(1).map { it.tabs.single().id })
    }
}
