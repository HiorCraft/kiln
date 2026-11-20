package dev.kiln.client.lua

import dev.kiln.client.lua.V2Host.Companion.item
import dev.kiln.client.lua.V2Host.Companion.pkg
import dev.kiln.client.lua.items.InventorySlot
import dev.kiln.client.lua.items.ItemHandle
import dev.kiln.client.lua.items.LuaItems
import dev.kiln.client.lua.sandbox.LuaScriptException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.luaj.vm2.LuaValue

/** Lua API v2: exports/imports, read-only views, permissions of the caller, budget of the caller, item handles. */
class LuaImportTest {

    private val kitSource = """
        local M = { version = 1, colors = { bg = 0xFF101010 }, list = { 10, 20, 30 }, counter = { n = 0 } }
        local Class = {}
        Class.__index = Class
        function Class.hello() return "hi" end
        M.obj = setmetatable({}, Class)
        function M.add(a, b) return a + b end
        function M.fresh() return { n = 0 } end
        function M.bump(t) t.n = t.n + 1 return t.n end
        function M.spin(n) local x = 0 for i = 1, n do x = x + i end return x end
        function M.shared() return M.colors end
        function M.protected() return kiln.readonly(M.colors) end
        function M.peek() return M.colors.bg, M.counter.n end
        kiln.export(M)
        assert(not pcall(kiln.export, M)) -- only once
    """.trimIndent()

    @Test
    fun `exports are read-only for importers, recursively`() {
        val host = V2Host()
        val kit = host.load(pkg("ui_kit", files = arrayOf("main.lua" to kitSource)))
        host.load(pkg("shop", deps = listOf("ui_kit"), files = arrayOf("main.lua" to """
            local ui = kiln.import("ui_kit")
            assert(ui.version == 1 and ui.add(2, 3) == 5)
            assert(not pcall(function() ui.version = 2 end))
            assert(not pcall(function() ui.newkey = 1 end))
            assert(not pcall(function() ui.colors.bg = 0 end))
            assert(not pcall(function() ui.add = print end))
            assert(not pcall(setmetatable, ui, {}))
            assert(getmetatable(ui) == "read-only")
            assert(not pcall(table.insert, ui.list, 40))
            assert(not pcall(table.remove, ui.list))
            assert(not pcall(table.sort, ui.list))
            assert(#ui.list == 3 and ui.list[2] == 20)
            local n = 0
            for i, v in ipairs(ui.list) do n = n + v end
            assert(n == 60)
            local keys = 0
            for k, v in pairs(ui) do keys = keys + 1 end
            assert(keys >= 10)
            assert(ui.colors == ui.colors and ui.add == ui.add) -- wrappers are cached
            assert(ui.obj.hello() == "hi") -- reads go through the exporter's metatables
            -- return values are the exporter's choice: fresh tables are mutable ...
            local t = ui.fresh(); t.n = 5; assert(t.n == 5)
            -- ... readonly() protects shared ones
            assert(not pcall(function() ui.protected().bg = 1 end))
            -- a proxy handed back to the exporter is the real table again
            assert(ui.bump(ui.counter) == 1 and ui.counter.n == 1)
            -- only declared dependencies can be imported
            assert(not pcall(kiln.import, "other"))
        """.trimIndent())))
        assertTrue(host.failures.isEmpty(), host.failures.toString())
        // the exporter's own table was never changed by the importer (except through its own function)
        val m = kit.exported
        assertEquals(1, m.get("version").toint())
        assertEquals(3, m.get("list").length())
        assertEquals(1, m.get("counter").get("n").toint())
        assertTrue(m.get("newkey").isnil())
    }

    @Test
    fun `export only while loading and import needs an export`() {
        val host = V2Host()
        val lazy = host.load(pkg("lazy", setOf("channels"), files = arrayOf("main.lua" to """
            kiln.net.channel("late"):on(function() kiln.export({}) end)
        """.trimIndent())))
        assertThrows(LuaScriptException::class.java) {
            host.load(pkg("user", deps = listOf("lazy"), files = arrayOf("main.lua" to "kiln.import('lazy')")))
        }
        lazy.onChannelMessage("late", "{}")
        assertFalse(lazy.alive)
        assertTrue(host.failures.single().contains("only allowed while the extension loads"), host.failures.toString())
    }

    @Test
    fun `permissions cannot be borrowed through an import`() {
        val host = V2Host()
        val kit = host.load(pkg("ui_kit", setOf("channels", "keybinds", "toasts"), files = arrayOf("main.lua" to """
            local ch = kiln.net.channel("x")
            local stored
            kiln.keys.bind("go", { press = function() if stored then stored() end end })
            kiln.export({
              send = function(m) return ch:send(m) end,
              channel = ch,
              toast = function() kiln.ui.toast("a", "b") end,
              register = function(fn) stored = fn end,
            })
        """.trimIndent())))
        // without the permission: the API extension's channel cannot be used, neither through a function nor directly
        val poor = host.load(pkg("poor", setOf("widgets"), listOf("ui_kit"), "main.lua" to """
            local ui = kiln.import("ui_kit")
            local ok, err = pcall(ui.send, { a = 1 })
            assert(not ok and err:find("permission 'channels' missing", 1, true), err)
            ok, err = pcall(function() ui.channel:send({ a = 1 }) end)
            assert(not ok and err:find("permission 'channels' missing", 1, true), err)
            assert(not pcall(ui.toast))
            result = "checked"
        """.trimIndent()))
        assertEquals("checked", poor.runtime.globals.get("result").tojstring())
        assertTrue(host.sent.isEmpty())

        // with the permission it works (and the message goes out on the API extension's channel)
        val rich = host.load(pkg("rich", setOf("channels"), listOf("ui_kit"), "main.lua" to """
            local ui = kiln.import("ui_kit")
            assert(ui.send({ a = 1 }))
            local mine = kiln.net.channel("mine")
            ui.register(function() mine:send({ from = "callback" }) end)
        """.trimIndent()))
        assertEquals(listOf("ui_kit:x"), host.sent.map { it.first })

        // the API extension calls the stored callback in its own call (principal ui_kit, which has channels): allowed;
        // a callback of an extension WITHOUT a permission the principal lacks is the other direction:
        kit.onKey("go", true)
        assertEquals("rich:mine", host.sent.last().first)
        assertTrue(rich.alive && kit.alive)

        // principal without channels calling into an extension with channels through a callback chain fails
        val kit2 = host.load(pkg("kit2", setOf("keybinds"), files = arrayOf("main.lua" to """
            local stored
            kiln.keys.bind("go", { press = function() stored() end })
            kiln.export({ register = function(fn) stored = fn end })
        """.trimIndent())))
        host.load(pkg("rich2", setOf("channels"), listOf("kit2"), "main.lua" to """
            local mine = kiln.net.channel("mine")
            kiln.import("kit2").register(function() mine:send({ from = "borrowed" }) end)
        """.trimIndent()))
        val before = host.sent.size
        kit2.onKey("go", true)
        assertEquals(before, host.sent.size, "rich2's channel must not be usable in kit2's call")
        assertFalse(kit2.alive)
        assertTrue(host.failures.last().contains("permission 'channels' missing"), host.failures.toString())
    }

    @Test
    fun `the caller pays the instruction budget of imported code`() {
        val host = V2Host()
        val kit = host.load(pkg("ui_kit", files = arrayOf("main.lua" to kitSource)))
        val shop = host.load(pkg("shop", setOf("channels"), listOf("ui_kit"), "main.lua" to """
            local ui = kiln.import("ui_kit")
            kiln.net.channel("work"):on(function(m) result = ui.spin(m.n) end)
        """.trimIndent()))
        val kitBefore = kit.runtime.lastCallInstructions
        shop.onChannelMessage("work", """{"n":2000}""")
        assertTrue(shop.alive)
        assertEquals(2001000.0, shop.runtime.globals.get("result").todouble())
        assertTrue(shop.runtime.lastCallInstructions > 2000 * 2, "charged to the caller: ${shop.runtime.lastCallInstructions}")
        assertEquals(kitBefore, kit.runtime.lastCallInstructions)

        // a loop in imported code exhausts the caller's budget: the caller is unloaded, the API extension stays
        shop.onChannelMessage("work", """{"n":100000000}""")
        assertFalse(shop.alive)
        assertTrue(kit.alive)
        assertTrue(host.failures.single().startsWith("shop:") && host.failures.single().contains("instruction budget"), host.failures.toString())
    }

    @Test
    fun `functions of an unloaded API extension refuse to run`() {
        val host = V2Host()
        val kit = host.load(pkg("ui_kit", files = arrayOf("main.lua" to kitSource)))
        val shop = host.load(pkg("shop", setOf("channels"), listOf("ui_kit"), "main.lua" to """
            local ui = kiln.import("ui_kit")
            kiln.net.channel("go"):on(function() ui.add(1, 2) end)
        """.trimIndent()))
        kit.stop()
        shop.onChannelMessage("go", "{}")
        assertFalse(shop.alive)
        assertTrue(host.failures.single().contains("'ui_kit' is unloaded"), host.failures.toString())
    }

    @Test
    fun `item handles are opaque, cached and limited`() {
        val host = V2Host()
        val ext = host.load(pkg("shop", setOf("widgets"), files = arrayOf("main.lua" to """
            local a = kiln.items.decode("${item("minecraft:diamond_sword")}")
            assert(a and a.id == "minecraft:diamond_sword" and a.name == "diamond sword" and a.maxStack == 64)
            assert(kiln.items.decode("${item("minecraft:diamond_sword")}") == a) -- cached
            assert(getmetatable(a) == "item")
            assert(not pcall(setmetatable, a, {}))
            assert(a.anything == nil)
            local bad, reason = kiln.items.decode("!!!")
            assert(bad == nil and reason == "not Base64")
            bad, reason = kiln.items.decode("aGVsbG8=")
            assert(bad == nil and reason:find("gzip"))
            assert(not pcall(kiln.items.decode, string.rep("A", 90000)))
            handle = a
        """.trimIndent())))
        assertTrue(ext.alive)
        assertEquals(1, host.decodes, "second decode of the same text came from the cache")

        // rate limit of cache misses, LRU size
        var now = 0L
        val items = LuaItems({ FakeItem("x") }, clock = { now })
        val texts = (0 until LuaItems.MAX_DECODES_PER_SECOND + 1).map { item("minecraft:i$it") }
        for (t in texts.dropLast(1)) assertNotNull(items.decode(t).arg1() as? ItemHandle)
        assertThrows(org.luaj.vm2.LuaError::class.java) { items.decode(texts.last()) }
        now += 1_000_000_000L
        items.decode(texts.last())
        repeat(3) { round ->
            now += 1_000_000_000L
            for (i in 0 until 250) items.decode(item("minecraft:r${round}_$i"))
        }
        assertEquals(LuaItems.CACHE_SIZE, items.cached)

        // drawing a handle uses the full stack path; tooltips only where allowed
        val target = CountingTarget()
        val w = LuaExtension(pkg("draw", setOf("widgets"), files = arrayOf("main.lua" to """
            kiln.ui.widget("w", { render = function(self, g, w)
              g:item(handle, 0, 0, { size = 32, count = 1500 })
              g:item("minecraft:stone", 0, 0)
              g:itemTooltip(handle, 4, 4, { "Preis: 5" })
            end })
        """.trimIndent())), host)
        w.runtime.globals.set("handle", ext.runtime.globals.get("handle"))
        w.load()
        w.createWidget("w")!!.render(target, 0, 0, 10, 10, emptyMap(), true, 0.0, 0.0)
        assertEquals(2, target.items)
        assertEquals(listOf("item:minecraft:diamond_sword"), target.tooltips)
    }

    @Test
    fun `inventory needs its permission and returns copies`() {
        val host = V2Host()
        host.inventorySlots = listOf(InventorySlot(0, FakeItem("minecraft:dirt"), 12), InventorySlot(40, FakeItem("minecraft:shield"), 1))
        host.load(pkg("a", files = arrayOf("main.lua" to "assert(kiln.player().inventory == nil)")))
        val b = host.load(pkg("b", setOf("inventory"), files = arrayOf("main.lua" to """
            local inv = kiln.player().inventory()
            assert(#inv == 2 and inv[1].slot == 0 and inv[1].count == 12 and inv[1].id == "minecraft:dirt")
            assert(inv[2].slot == 40 and inv[2].handle.id == "minecraft:shield")
            inv[1].count = 99 -- a copy
            assert(kiln.player().inventory()[1].count == 12)
        """.trimIndent())))
        assertTrue(b.alive)
        // through an import, the caller needs the permission too
        host.load(pkg("inv_api", setOf("inventory"), files = arrayOf("main.lua" to """
            kiln.export({ get = function() return kiln.player().inventory() end })
        """.trimIndent())))
        val user = host.load(pkg("user", deps = listOf("inv_api"), files = arrayOf("main.lua" to """
            local ok, err = pcall(kiln.import("inv_api").get)
            assert(not ok and err:find("permission 'inventory' missing", 1, true), err)
        """.trimIndent())))
        assertTrue(user.alive)
    }

    @Test
    fun `api version and helpers`() {
        val host = V2Host()
        host.clockSeconds = 12.5
        val ext = host.load(pkg("x", files = arrayOf("main.lua" to """
            assert(kiln.apiVersion == 2)
            assert(kiln.clock() == 12.5)
            assert(kiln.ui.viewport() == nil) -- the test host has no window
            assert(kiln.ui.screen == nil and kiln.ui.open == nil and kiln.ui.sound == nil)
            local ro = kiln.readonly({ a = { b = 1 } })
            assert(ro.a.b == 1 and not pcall(function() ro.a.b = 2 end))
        """.trimIndent())))
        assertTrue(ext.alive)
        assertSame(LuaValue.NIL, ext.exported)
    }
}
