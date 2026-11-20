package dev.kiln.client.lua

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `kiln.sound.play` and `g:text{font}` (permission `resources`, own namespace or dependencies). */
class LuaResourcesTest {
    private fun host() = V2Host()

    @Test
    fun soundNeedsResourcesPermission() {
        val host = host()
        val ext = host.load(V2Host.pkg("demo", setOf("hud"), emptyList(), "main.lua" to "has = kiln.sound ~= nil"))
        assertEquals(false, ext.runtime.globals.get("has").toboolean())
    }

    @Test
    fun soundPlaysOwnNamespace() {
        val host = host()
        host.load(V2Host.pkg("demo", setOf("resources"), emptyList(), "main.lua" to """kiln.sound.play("demo:chime", { volume = 0.5, pitch = 2 })"""))
        assertEquals(listOf("demo:chime@0.5/2.0"), host.sounds)
    }

    @Test
    fun soundRejectsForeignNamespace() {
        val host = host()
        val e = runCatching {
            host.load(V2Host.pkg("demo", setOf("resources"), emptyList(), "main.lua" to """kiln.sound.play("minecraft:entity.creeper.primed")"""))
        }.exceptionOrNull()
        assertTrue(e?.message?.contains("namespace 'minecraft' not allowed") == true, e?.message)
    }

    @Test
    fun soundAllowsDependencyNamespace() {
        val host = host()
        host.load(V2Host.pkg("lib", setOf("resources"), emptyList(), "main.lua" to "return {}"))
        host.load(V2Host.pkg("demo", setOf("resources"), listOf("lib"), "main.lua" to """kiln.sound.play("lib:click")"""))
        assertEquals(listOf("lib:click@1.0/1.0"), host.sounds)
    }

    private fun renderHud(host: V2Host, permissions: Set<String>, font: String): Pair<CountingTarget, LuaExtension> {
        val ext = host.load(V2Host.pkg("demo", permissions + "hud", emptyList(), "main.lua" to """
            kiln.hud.layer("x", { anchor = "top_left", width = 50, height = 20 }, function(g, area)
              g:text("Hallo", 0, 0, { font = "$font" })
            end)
        """.trimIndent()))
        val target = CountingTarget()
        ext.renderHud(ext.hudLayers.first(), target, 0, 0)
        return target to ext
    }

    @Test
    fun fontOptionPassedToTarget() {
        val (target, _) = renderHud(host(), setOf("resources"), "demo:runes")
        assertEquals(listOf("demo:runes"), target.fonts)
    }

    @Test
    fun fontRejectsForeignNamespace() {
        val host = host()
        val (target, ext) = renderHud(host, setOf("resources"), "minecraft:alt")
        assertTrue(target.fonts.isEmpty())
        assertTrue(!ext.alive && host.failures.last().contains("namespace 'minecraft' not allowed"), host.failures.toString())
    }

    @Test
    fun fontNeedsResourcesPermission() {
        val host = host()
        val (target, ext) = renderHud(host, emptySet(), "demo:runes")
        assertTrue(target.fonts.isEmpty())
        assertTrue(!ext.alive && host.failures.last().contains("permission 'resources'"), host.failures.toString())
    }
}
