package dev.kiln.client.lua

import com.google.gson.JsonParser
import dev.kiln.client.ext.ExtensionPackage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class LuaExtensionTest {

    /** Records everything the extension asks of the host. */
    private class FakeHost : ExtensionHost {
        val sent = ArrayList<Pair<String, String>>()
        val toasts = ArrayList<Pair<String, String>>()
        val keys = ArrayList<Triple<String, String, String>>()
        val failures = ArrayList<String>()
        val logs = ArrayList<String>()
        var canSend = true
        var time = 0.0

        override fun send(channel: String, json: String): Boolean = canSend.also { if (it) sent += channel to json }
        override fun toast(extension: LuaExtension, title: String, text: String) { toasts += title to text }
        override fun bindKey(extension: LuaExtension, id: String, name: String, defaultKey: String) { keys += Triple(id, name, defaultKey) }
        override fun time(): Double = time
        override fun player(): PlayerSnapshot = PlayerSnapshot("Steve", 1.0, 64.0, -3.5)
        override fun log(extension: LuaExtension, message: String) { logs += message }
        override fun failed(extension: LuaExtension, error: LuaScriptException) { failures += error.message ?: "" }
    }

    /** Records draw calls. */
    private class Recorder : DrawTarget {
        val calls = ArrayList<String>()
        var fills = 0
        override fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) { fills++ }
        override fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) { calls += "line" }
        override fun text(text: String, x: Int, y: Int, color: Int, shadow: Boolean) { calls += "text:$text@$x,$y" }
        override fun textWidth(text: String): Int = text.length * 6
        override fun textureSize(id: String): IntArray? = if (id == "x:textures/icon.png") intArrayOf(16, 16) else null
        override fun texture(id: String, x: Int, y: Int, w: Int, h: Int, u: Float, v: Float, regionWidth: Int, regionHeight: Int, textureWidth: Int, textureHeight: Int) {
            calls += "texture:$id@$x,$y ${w}x$h r=${regionWidth}x$regionHeight t=${textureWidth}x$textureHeight"
        }
        override fun item(itemId: String, x: Int, y: Int, size: Int, count: Int, decorations: Boolean) { calls += "item:$itemId@$x,$y" }
        override fun tooltip(text: String) { calls += "tooltip:$text" }
    }

    private fun pkg(id: String, permissions: Set<String>, vararg files: Pair<String, String>) =
        ExtensionPackage(id, "1", id, "main.lua", permissions, files.associate { it.first to it.second.toByteArray() })

    private fun exampleDir(): Path = Paths.get("..", "example-server", "src", "main", "resources", "extensions", "kiln_example")

    private fun example(): ExtensionPackage {
        val dir = exampleDir()
        val files = Files.walk(dir).use { s ->
            s.filter(Files::isRegularFile).toList().associate { dir.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it) }
        }
        val manifest = JsonParser.parseString(String(files.getValue("extension.json"))).asJsonObject
        return ExtensionPackage(
            manifest["id"].asString, manifest["version"].asString, manifest["name"].asString, "main.lua",
            manifest["permissions"].asJsonArray.map { it.asString }.toSet(), files,
        )
    }

    @Test
    fun `example extension loads and works with the API`() {
        val host = FakeHost()
        val ext = LuaExtension(example(), host)
        ext.load()
        assertTrue(ext.hasWidget("gauge"))
        assertEquals(listOf(Triple("hello", "Hallo an Server", "key.keyboard.j")), host.keys)

        // widget: render with the server's string properties, hover shows a tooltip
        val gauge = ext.createWidget("gauge")!!
        val target = Recorder()
        val props = mapOf("value" to "0.75", "label" to "Energie")
        gauge.render(target, 10, 20, 40, 40, props, hovered = true, mouseX = 30.0, mouseY = 30.0)
        assertTrue(target.fills > 20, "arc pixels drawn: ${target.fills}")
        assertTrue(target.calls.any { it.startsWith("text:75%") }, target.calls.toString())
        assertTrue(target.calls.any { it.startsWith("text:Energie") }, target.calls.toString())
        assertTrue(target.calls.any { it.startsWith("tooltip:Energie: 75") }, target.calls.toString())
        assertTrue(gauge.mouseClicked(40, 40, props, 5.0, 5.0, 0))

        // keybind -> channel; reply -> toast
        ext.onKey("hello", true)
        assertEquals("kiln_example:hello", host.sent.single().first)
        assertEquals("Hallo vom Client, ich bin Steve!", JsonParser.parseString(host.sent.single().second).asJsonObject["text"].asString)
        ext.onChannelMessage("hello", """{"text":"Hallo zurück"}""")
        assertEquals(listOf("Server" to "Hallo zurück"), host.toasts)

        // HUD: nothing while the machine is off, drawn after a state message
        val layer = ext.hudLayers.single()
        assertEquals("top_right", layer.anchor)
        val hud = Recorder()
        ext.renderHud(layer, hud, 100, 4)
        assertEquals(0, hud.fills)
        ext.onChannelMessage("machine", """{"running":true,"energy":0.5,"output":12}""")
        ext.renderHud(layer, hud, 100, 4)
        assertTrue(hud.calls.contains("item:minecraft:blast_furnace@103,7"), hud.calls.toString())
        assertTrue(hud.calls.any { it.startsWith("text:Output: 12") }, hud.calls.toString())
        assertTrue(host.failures.isEmpty(), host.failures.toString())
    }

    @Test
    fun `api parts without permission are nil`() {
        val host = FakeHost()
        val ext = LuaExtension(pkg("x", emptySet(), "main.lua" to """
            assert(kiln.ui.widget == nil and kiln.ui.tooltip == nil and kiln.ui.toast == nil)
            assert(kiln.hud == nil and kiln.keys == nil and kiln.net == nil)
            assert(type(kiln.ui.lerpColor) == 'function' and type(kiln.time) == 'function')
            assert(kiln.player().name == 'Steve')
        """.trimIndent()), host)
        ext.load()
        val all = LuaExtension(pkg("y", setOf("widgets", "keybinds", "hud", "channels", "toasts"), "main.lua" to """
            assert(kiln.ui.widget and kiln.ui.toast and kiln.hud.layer and kiln.keys.bind and kiln.net.channel)
        """.trimIndent()), host)
        all.load()
    }

    @Test
    fun `widget state survives and errors unload the extension`() {
        val host = FakeHost()
        val ext = LuaExtension(pkg("x", setOf("widgets"), "main.lua" to """
            kiln.ui.widget("x:counter", {
              init = function(self) self.n = 0 end,
              render = function(self, g, w)
                self.n = self.n + 1
                g:text(tostring(self.n), 0, 0)
                g:texture("textures/icon.png", 1, 2, 8, 8)
                if w.props.crash == "yes" then error("boom") end
              end,
            })
        """.trimIndent()), host)
        ext.load()
        assertThrows(Exception::class.java) { LuaExtension(pkg("z", setOf("widgets"), "main.lua" to "kiln.ui.widget('other:w', {})"), host).load() }
        val w = ext.createWidget("counter")!!
        val r = Recorder()
        w.render(r, 0, 0, 10, 10, emptyMap(), false, 0.0, 0.0)
        w.render(r, 0, 0, 10, 10, emptyMap(), false, 0.0, 0.0)
        assertEquals(listOf("text:1@0,0", "texture:x:textures/icon.png@1,2 8x8 r=16x16 t=16x16", "text:2@0,0"), r.calls.take(3))
        assertNull(ext.createWidget("missing"))
        w.render(r, 0, 0, 10, 10, mapOf("crash" to "yes"), false, 0.0, 0.0)
        assertFalse(ext.alive)
        assertEquals(1, host.failures.size)
        assertTrue(host.failures[0].contains("boom"), host.failures[0])
        assertNull(ext.createWidget("counter"))
    }

    @Test
    fun `budget overrun at runtime unloads, at load time throws`() {
        val host = FakeHost()
        val ext = LuaExtension(pkg("x", setOf("channels"), "main.lua" to """
            kiln.net.channel("loop"):on(function(msg) while true do end end)
        """.trimIndent()), host)
        ext.load()
        ext.onChannelMessage("loop", "{}")
        assertFalse(ext.alive)
        assertTrue(host.failures.single().contains("instruction budget"))
        assertThrows(LuaScriptException::class.java) { LuaExtension(pkg("y", emptySet(), "main.lua" to "while true do end"), host).load() }
    }

    @Test
    fun `require loads package files only, once`() {
        val host = FakeHost()
        val ext = LuaExtension(pkg("x", emptySet(),
            "main.lua" to """
                local u = kiln.require("lib/util")
                assert(u.twice(2) == 4)
                assert(kiln.require("lib/util.lua") == u)
                assert(not pcall(kiln.require, "../secret"))
                assert(not pcall(kiln.require, "missing"))
                counter = loads
            """.trimIndent(),
            "lib/util.lua" to "loads = (loads or 0) + 1 return { twice = function(n) return n * 2 end }",
        ), host)
        ext.load()
        assertEquals(1, ext.runtime.globals.get("counter").toint())
    }

    @Test
    fun `channel names, limits and rate`() {
        val host = FakeHost()
        val ext = LuaExtension(pkg("x", setOf("channels"), "main.lua" to """
            ch = kiln.net.channel("data")
            assert(ch == kiln.net.channel("data"))
            assert(not pcall(kiln.net.channel, "Bad Name"))
            assert(not pcall(ch.send, ch, { f = function() end }))
            sent = 0
            for i = 1, 100 do if ch:send({ i = i }) then sent = sent + 1 end end
        """.trimIndent()), host)
        ext.load()
        assertEquals(40, ext.runtime.globals.get("sent").toint())
        assertEquals("x:data", host.sent.first().first)
        // invalid JSON from the server is dropped, not a script error
        ext.onChannelMessage("data", "{oops")
        assertTrue(ext.alive)
    }

    @Test
    fun `keybinds only while loading`() {
        val host = FakeHost()
        val ext = LuaExtension(pkg("x", setOf("keybinds", "channels"), "main.lua" to """
            kiln.keys.bind("dash", { name = "Dash", default = "G", press = function() pressed = true end })
            kiln.net.channel("late"):on(function() kiln.keys.bind("late", {}) end)
        """.trimIndent()), host)
        ext.load()
        assertEquals(Triple("dash", "Dash", "key.keyboard.g"), host.keys.single())
        ext.onKey("dash", true)
        assertTrue(ext.runtime.globals.get("pressed").toboolean())
        ext.onChannelMessage("late", "{}")
        assertFalse(ext.alive)
    }

    @Test
    fun `colors and arcs`() {
        assertEquals(0xFF2A2F2C.toInt(), LuaExtension.color(org.luaj.vm2.LuaValue.valueOf(4280954668.0)))
        assertEquals(0xFF808080.toInt(), LuaExtension.lerpColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5))
        // upper half ring: left half for 0..0.5, nothing below the center
        val xs = HashSet<Int>()
        var maxY = Int.MIN_VALUE
        LuaExtension.arcRuns(20.0, 20.0, 10.0, 3.0, 0.0, 0.5) { x, _, y1 -> xs += x; maxY = maxOf(maxY, y1) }
        assertTrue(xs.all { it < 20 }, xs.toString())
        assertTrue(maxY <= 20)
        var count = 0
        LuaExtension.arcRuns(20.0, 20.0, 10.0, 3.0, 0.0, 1.0) { _, _, _ -> count++ }
        assertTrue(count > xs.size)
    }
}
