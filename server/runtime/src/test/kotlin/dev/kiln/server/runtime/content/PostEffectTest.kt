package dev.kiln.server.runtime.content

import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PostEffectTest {
    @Test
    fun postEffectRejectsUnknownNamespace() {
        assertNotNull(PostEffects.problem(Key.key("minecraft", "creeper"), setOf("kiln_content")))
        assertNotNull(PostEffects.problem(Key.key("ghost", "wobble"), setOf("kiln_content")))
    }

    @Test
    fun postEffectAllowsExtensionNamespaceAndOff() {
        assertNull(PostEffects.problem(Key.key("kiln_content", "wobble"), setOf("kiln_content")))
        assertNull(PostEffects.problem(null, emptySet()))
    }
}
