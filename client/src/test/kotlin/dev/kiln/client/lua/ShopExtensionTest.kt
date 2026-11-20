package dev.kiln.client.lua

import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.client.lua.LuaScreenSession.KeyResult
import dev.kiln.client.lua.LuaScreenSession.Mods
import dev.kiln.client.lua.V2Host.Companion.item
import dev.kiln.client.lua.V2Host.Companion.pkg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.luaj.vm2.LuaValue
import java.io.File

/**
 * The real `ui_kit` and `shop` extensions of the example server (`example/server/src/main/resources/extensions/`)
 * in the real sandbox: loading, component logic, client-side filtering, the shop flows and the render budget.
 */
class ShopExtensionTest {
    private val none = Mods(false, false, false)
    private val shift = Mods(true, false, false)

    private fun extensionDir(id: String): File {
        val candidates = listOf(File("../example/server/src/main/resources/extensions/$id"), File("example/server/src/main/resources/extensions/$id"))
        return candidates.firstOrNull { it.isDirectory } ?: error("extension $id not found from ${File(".").absolutePath}")
    }

    /** Reads an extension folder like the server packs it (permissions/dependencies from extension.json). */
    private fun realPackage(id: String): ExtensionPackage {
        val dir = extensionDir(id)
        val files = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
        val json = com.google.gson.JsonParser.parseString(files.getValue("extension.json").toString(Charsets.UTF_8)).asJsonObject
        val permissions = json.getAsJsonArray("permissions").map { it.asString }.toSet()
        val deps = json.getAsJsonArray("dependencies")?.map { it.asString } ?: emptyList()
        return ExtensionPackage(id, json["version"].asString, json["name"].asString, "main.lua", permissions, files, deps)
    }

    private fun loadShop(host: V2Host = V2Host()): Pair<V2Host, LuaExtension> {
        host.load(realPackage("ui_kit"))
        val shop = host.load(realPackage("shop"))
        assertTrue(shop.alive, host.failures.toString())
        return host to shop
    }

    private fun offer(id: Int, name: String, key: String, cat: String, price: Int, amount: Int, seller: String = "Server", own: Boolean = false, created: Long = id.toLong()) =
        """{"id":$id,"item":"${item(key)}","name":"$name","key":"$key","cat":"$cat","price":$price,"amount":$amount,"seller":"$seller","own":$own,"created":$created,"expiresIn":3600000}"""

    private val categories = """[{"id":"ALL","label":"Alle"},{"id":"BLOCKS","label":"Blöcke"},{"id":"TOOLS","label":"Werkzeuge"},{"id":"ORES","label":"Erze"},{"id":"FOOD","label":"Essen"},{"id":"OTHER","label":"Sonstiges"}]"""
    private val durations = """[{"id":"H1","label":"1 Stunde"},{"id":"H12","label":"12 Stunden"},{"id":"H24","label":"24 Stunden"},{"id":"D7","label":"7 Tage"}]"""

    private fun state(offers: List<String>, mine: List<String> = emptyList(), balance: Long = 1000, extra: String = "") =
        """{"balance":$balance,"startBalance":1000,"maxPrice":1000000,"categories":$categories,"durations":$durations,""" +
            """"offers":[${offers.joinToString(",")}],"mine":[${mine.joinToString(",")}],"suggest":{"minecraft:oak_log":"BLOCKS"}$extra}"""

    private val demoOffers = listOf(
        offer(1, "Oak Log", "minecraft:oak_log", "BLOCKS", 2, 640, created = 10),
        offer(2, "Diamond", "minecraft:diamond", "ORES", 120, 32, created = 20),
        offer(3, "Diamond Pickaxe", "minecraft:diamond_pickaxe", "TOOLS", 900, 1, "Greta", created = 30),
        offer(4, "Äpfel", "minecraft:apple", "FOOD", 4, 64, "Hans", created = 40),
        offer(5, "Glass", "minecraft:glass", "BLOCKS", 3, 256, "Steve", own = true, created = 50),
    )

    private fun lua(ext: LuaExtension, path: String): LuaValue = path.split('.').fold(ext.runtime.globals as LuaValue) { t, k -> t.get(k) }

    private fun call(ext: LuaExtension, fn: LuaValue, vararg args: LuaValue): org.luaj.vm2.Varargs = ext.invoke(fn, LuaValue.varargsOf(args))!!

    private fun frame(session: LuaScreenSession, mx: Int = 0, my: Int = 0, target: CountingTarget = CountingTarget()): CountingTarget {
        session.render(target, mx, my)
        return target
    }

    /** Click like a player: the frame before knows the regions, the frame after reports the click. */
    private fun click(session: LuaScreenSession, x: Int, y: Int, mods: Mods = none): Boolean {
        frame(session, x, y)
        val used = session.mouseClicked(x.toDouble(), y.toDouble(), 0, mods)
        session.mouseReleased(x.toDouble(), y.toDouble(), 0)
        frame(session, x, y)
        return used
    }

    @Test
    fun `both extensions load and the server opens the shop`() {
        val (host, shop) = loadShop()
        assertNull(host.shown)
        shop.onChannelMessage("open", state(demoOffers))
        val session = host.shown!!
        assertEquals("market", session.name)
        val target = frame(session, 200, 100)
        assertTrue(target.items >= 5, "offers are drawn")
        assertTrue(shop.alive, host.failures.toString())
        // the search field is a native input
        assertEquals(listOf("search"), session.textInputs.map { it.id })
        // closing reports it to the server
        assertEquals(KeyResult.CLOSE, host.escape())
        assertEquals("shop:closed" to "{}", host.sent.last())
    }

    @Test
    fun `filter and sort run client-side in Lua`() {
        val (host, shop) = loadShop()
        shop.onChannelMessage("open", state(demoOffers))
        val f = lua(shop, "shop_debug.F")
        val offers = lua(shop, "shop_debug.M.offers")
        fun ids(category: String, search: String, sort: String): List<Int> {
            val list = call(shop, f.get("query"), offers, LuaValue.valueOf(category), LuaValue.valueOf(search), LuaValue.valueOf(sort)).arg1()
            return (1..list.length()).map { list.get(it).get("id").toint() }
        }
        assertEquals(listOf(5, 4, 3, 2, 1), ids("ALL", "", "NEWEST"))
        assertEquals(listOf(1, 5, 4, 2, 3), ids("ALL", "", "PRICE_ASC"))
        assertEquals(listOf(3, 2, 4, 5, 1), ids("ALL", "", "PRICE_DESC"))
        assertEquals(listOf(4, 2, 3, 5, 1), ids("ALL", "", "NAME")) // "Äpfel" sorts with A
        assertEquals(listOf(2, 3), ids("ALL", "dia", "PRICE_ASC"))
        assertEquals(listOf(3), ids("ALL", "DIAMOND greta", "NEWEST")) // all words, case-insensitive, seller
        assertEquals(listOf(3), ids("TOOLS", "", "NEWEST"))
        assertEquals(listOf(4), ids("ALL", "ÄPF", "NEWEST")) // umlauts are lowered too
        assertEquals(listOf(1), ids("ALL", "oak log", "NEWEST")) // item key with spaces
        assertEquals(emptyList<Int>(), ids("FOOD", "dia", "NEWEST"))
        val counts = call(shop, f.get("counts"), offers, lua(shop, "shop_debug.M.categories"), LuaValue.valueOf("")).arg1()
        assertEquals(5, counts.get("ALL").toint())
        assertEquals(2, counts.get("BLOCKS").toint())
        assertEquals(0, counts.get("OTHER").toint())
        assertTrue(shop.alive, host.failures.toString())
    }

    @Test
    fun `typing in the search field filters without asking the server`() {
        val (host, shop) = loadShop()
        shop.onChannelMessage("open", state(demoOffers))
        val session = host.shown!!
        frame(session)
        val sentBefore = host.sent.size
        val search = host.view.boxes.single()
        search.type("glas")
        val target = frame(session)
        assertEquals(1, lua(shop, "shop_debug.V.cache.list").length())
        assertEquals(1, target.items)
        assertEquals(sentBefore, host.sent.size, "no server round trip per key")
    }

    @Test
    fun `buy flow - slider and field stay in sync, confirmation sends the request, result closes it`() {
        val (host, shop) = loadShop()
        shop.onChannelMessage("open", state(demoOffers))
        val session = host.shown!!
        session.layout(400, 300, 2.0)
        // market area: x 124, tiles from y 74, one column at this width; sorted by newest: 5 (own), 4, 3, ...
        click(session, 200, 74 + 48 + 10) // second tile = offer 4 (apples, 4 Taler, 64 left)
        val buy = lua(shop, "shop_debug.V.buy")
        assertEquals(4, buy.get("id").toint())
        frame(session)
        // modal 300x196 centred in 400x300: x 50..350, y 52..248; content from x 58, y 79
        val amountField = host.view.boxes.last()
        assertEquals("1", amountField.value)
        // a preset value must be set after the box got its size (vanilla EditBox scrolls it out of view otherwise)
        assertEquals("place", amountField.calls.first())
        assertTrue(session.textInputs.none { it.id == "search" }, "the search field is hidden behind the modal")
        amountField.type("0") // "10"
        frame(session)
        assertEquals(10, lua(shop, "shop_debug.V.buy.amount").toint())
        // slider: x 98 (content x 58 + 40), w 186 (content 284 - 40 - field 54 - 4); right end -> 64
        click(session, 98 + 185, 79 + 38 + 10)
        assertEquals(64, lua(shop, "shop_debug.V.buy.amount").toint())
        frame(session)
        assertEquals("64", amountField.value, "slider changes reach the field")
        // "Kaufen" (x 270..350 -> content right end 342 - 80, y 248 - 6 - 20)
        click(session, 300, 248 - 6 - 10)
        assertTrue(lua(shop, "shop_debug.V.buy.confirm").toboolean())
        // a click on the base layer is blocked by the modals
        frame(session)
        // confirmation 280x92 centred: x 60..340, y 104..196; "Ja, kaufen" at content mid 200 - 92
        click(session, 200 - 92 + 40, 196 - 6 - 10)
        val (channel, json) = host.sent.last()
        assertEquals("shop:buy", channel)
        val msg = com.google.gson.JsonParser.parseString(json).asJsonObject
        assertEquals(4, msg["offer"].asInt)
        assertEquals(64, msg["amount"].asInt)
        assertEquals(4, msg["price"].asInt)
        val req = msg["req"].asInt
        assertTrue(lua(shop, "shop_debug.V.buy.pending").toboolean())
        shop.onChannelMessage("result", """{"req":$req,"action":"buy","ok":true,"message":"Gekauft!"}""")
        assertTrue(lua(shop, "shop_debug.V.buy").isnil())
        assertTrue(host.sounds.contains("success"))
        assertTrue(shop.alive, host.failures.toString())
    }

    @Test
    fun `buy errors and live updates are shown in the dialog`() {
        val (host, shop) = loadShop()
        shop.onChannelMessage("open", state(demoOffers))
        val session = host.shown!!
        click(session, 200, 74 + 48 + 10)
        assertEquals(4, lua(shop, "shop_debug.V.buy.id").toint())
        // another player bought the rest: the offer disappears from the live state
        shop.onChannelMessage("state", state(demoOffers.filterIndexed { i, _ -> i != 3 }))
        val target = frame(session)
        assertTrue(target.texts > 0)
        assertFalse(lua(shop, "shop_debug.V.buy").isnil(), "dialog stays and says the offer is gone")
        // price change while open
        shop.onChannelMessage("state", state(demoOffers.map { it.replace("\"price\":4,", "\"price\":5,") }))
        frame(session)
        assertTrue(lua(shop, "shop_debug.V.buy.priceChanged").toboolean())
        assertTrue(shop.alive, host.failures.toString())
    }

    @Test
    fun `sell flow - inventory selection, shift for the stack, validation and request`() {
        val host = V2Host()
        host.inventorySlots = listOf(
            InventorySlot(0, FakeItem("minecraft:oak_log"), 20),
            InventorySlot(9, FakeItem("minecraft:oak_log"), 64),
            InventorySlot(10, FakeItem("minecraft:diamond"), 3),
        )
        val (_, shop) = loadShop(host)
        shop.onChannelMessage("open", state(demoOffers, extra = ""","tab":"sell""""))
        val session = host.shown!!
        assertEquals(2, lua(shop, "shop_debug.V.tab").toint())
        frame(session)
        // inventory panel at x 6, slots from x 12, first row (slots 9..17) at y 54
        click(session, 12 + 9, 54 + 9, shift) // slot 9, shift = whole stack
        val sell = lua(shop, "shop_debug.V.sell")
        assertEquals(9, sell.get("slot").toint())
        assertEquals("64", sell.get("amount").tojstring())
        assertEquals("BLOCKS", sell.get("category").tojstring(), "category suggested by the server")
        frame(session)
        // submit without price: errors, no confirmation
        val submit = findButtonY(session, shop)
        click(session, 300, submit)
        assertTrue(sell.get("submitted").toboolean())
        assertFalse(sell.get("confirm").toboolean())
        val boxes = session.textInputs.associate { it.id to it.box as FakeBox }
        boxes.getValue("sell_price").type("7")
        frame(session)
        click(session, 300, findButtonY(session, shop))
        assertTrue(sell.get("confirm").toboolean())
        frame(session)
        assertTrue(session.textInputs.isEmpty(), "fields hidden behind the confirmation")
        // confirmation 300x112 centred in 400x300: y 94..206, "Einstellen" left of the middle
        click(session, 200 - 50, 206 - 6 - 10)
        val (channel, json) = host.sent.last()
        assertEquals("shop:sell", channel)
        val msg = com.google.gson.JsonParser.parseString(json).asJsonObject
        assertEquals(9, msg["slot"].asInt)
        assertEquals("minecraft:oak_log", msg["item"].asString)
        assertEquals(64, msg["amount"].asInt)
        assertEquals(7, msg["price"].asInt)
        assertEquals("H24", msg["duration"].asString)
        assertEquals("BLOCKS", msg["category"].asString)
        assertTrue(msg["notify"].asBoolean)
        // server rejects with a field error
        shop.onChannelMessage("result", """{"req":${msg["req"].asInt},"action":"sell","ok":false,"error":"Ungültige Eingaben.","fields":{"amount":"Du hast nur 63 Stück."}}""")
        assertEquals("Du hast nur 63 Stück.", sell.get("fields").get("amount").tojstring())
        assertFalse(sell.get("pending").toboolean())
        assertTrue(shop.alive, host.failures.toString())
    }

    /** y of the "Angebot einstellen" button from the last frame's regions of the ui context. */
    private fun findButtonY(session: LuaScreenSession, shop: LuaExtension): Int {
        val ui = session.self.get("ui")
        val n = ui.get("n").toint()
        for (i in 1..n) {
            if (ui.get("rid").get(i).tojstring() == "sell_submit") return ui.get("ry").get(i).toint() + 10
        }
        error("no submit button")
    }

    @Test
    fun `my offers - change price and withdraw send requests, empty state`() {
        val (host, shop) = loadShop()
        val mine = """{"id":7,"item":"${item("minecraft:glass")}","name":"Glass","price":3,"amount":256,"expiresIn":7200000,"expired":false}"""
        shop.onChannelMessage("open", state(demoOffers, listOf(mine), extra = ""","tab":"mine""""))
        val session = host.shown!!
        frame(session)
        // row at y 52 (top 36 + 16), buttons at the right: "Preis ändern" x 400-6-168.., "Zurückziehen" x 400-6-84..
        click(session, 400 - 6 - 168 + 20, 52 + 10)
        assertEquals(7, lua(shop, "shop_debug.V.priceEdit.id").toint())
        frame(session)
        val field = session.textInputs.single().box as FakeBox
        assertEquals("3", field.value)
        field.type("5") // "35"
        assertEquals(KeyResult.HANDLED, session.keyPressed("enter", none))
        val (channel, json) = host.sent.last()
        assertEquals("shop:reprice", channel)
        assertEquals(35, com.google.gson.JsonParser.parseString(json).asJsonObject["price"].asInt)
        // Escape closes the dialog only while nothing is pending
        shop.onChannelMessage("result", """{"req":${com.google.gson.JsonParser.parseString(json).asJsonObject["req"].asInt},"ok":false,"error":"Preis ungültig"}""")
        assertEquals("Preis ungültig", lua(shop, "shop_debug.V.priceEdit.error").tojstring())
        assertEquals(KeyResult.HANDLED, host.escape())
        assertTrue(lua(shop, "shop_debug.V.priceEdit").isnil())
        frame(session)
        click(session, 400 - 6 - 84 + 20, 52 + 10)
        assertEquals(7, lua(shop, "shop_debug.V.withdraw.id").toint())
        frame(session)
        // modal 280x96 centred: y 102..198, "Zurückziehen" left of the middle
        click(session, 200 - 50, 198 - 6 - 10)
        assertEquals("shop:withdraw", host.sent.last().first)
        // offer gone -> empty state with a button to the sell tab
        shop.onChannelMessage("state", state(demoOffers))
        frame(session)
        click(session, 200, 52 + 50)
        assertEquals(2, lua(shop, "shop_debug.V.tab").toint())
        assertTrue(shop.alive, host.failures.toString())
    }

    // ---- ui_kit components ----------------------------------------------------------------------------------

    private val kitApp = """
        local kit = kiln.import("ui_kit")
        log = {}
        value, level, on, base, offset = "b", 3, false, 0, 0
        modal = false
        local options = {}
        for i = 1, 20 do options[i] = { value = string.char(96 + i), label = "Option " .. i } end
        kiln.ui.screen("t", {
          fullscreen = true,
          init = function(self) self.ui = kit.new() end,
          render = function(self, g, s)
            local ui = self.ui
            ui:frame(g, s)
            if ui:button("base", 10, 10, 60, 20, "Base") then base = base + 1 end
            ui:textInput("under", { x = 10, y = 40, w = 100, h = 20 })
            value = ui:dropdown("dd", 200, 10, 80, options, value, { rows = 5 })
            level = ui:slider("sl", 10, 70, 108, level, 0, 10, { step = 2 })
            on = ui:toggle("tg", 10, 100, on)
            offset = ui:scrollPanel("sp", 300, 100, 80, 100, 1000, function(cx, cy, cw)
              ui:button("inner", cx, cy + 20, cw, 20, "Inner")
            end)
            if modal then
              local mx, my, mw, mh, dismissed = ui:modal("m", 100, 60, { dismiss = true })
              if dismissed then modal = false end
            end
            ui:finish()
          end,
          click = function(self, s, mx, my, b, mods) return self.ui:click(mx, my, b, mods) end,
          release = function(self, s, mx, my, b) self.ui:release(mx, my, b) end,
          scroll = function(self, s, mx, my, dy) return self.ui:scroll(mx, my, dy) end,
          key = function(self, s, key, mods) return self.ui:key(key, mods) end,
        })
        kiln.net.channel("open"):on(function() kiln.ui.open("t") end)
    """.trimIndent()

    private fun kitHost(): Pair<V2Host, LuaExtension> {
        val host = V2Host()
        host.load(realPackage("ui_kit"))
        val app = host.load(pkg("app", setOf("screens", "channels"), listOf("ui_kit"), "main.lua" to kitApp))
        app.onChannelMessage("open", "{}")
        return host to app
    }

    private fun g(ext: LuaExtension, name: String) = ext.runtime.globals.get(name)

    @Test
    fun `dropdown opens over everything, picks with mouse and keyboard, closes on outside click`() {
        val (host, app) = kitHost()
        val session = host.shown!!
        click(session, 220, 20)
        frame(session)
        assertEquals("dd", session.self.get("ui").get("openDropdown").tojstring())
        // list below the box: rows of 14 px from y 31; option 3 (scroll 0)
        click(session, 220, 31 + 2 * 14 + 5)
        assertEquals("c", g(app, "value").tojstring())
        assertTrue(session.self.get("ui").get("openDropdown").isnil())
        // keyboard: open, down twice from the selection, enter
        click(session, 220, 20)
        assertEquals(KeyResult.HANDLED, session.keyPressed("down", none))
        assertEquals(KeyResult.HANDLED, session.keyPressed("down", none))
        assertEquals(KeyResult.HANDLED, session.keyPressed("enter", none))
        frame(session)
        assertEquals("e", g(app, "value").tojstring())
        // wheel scrolls the long list (20 options, 5 rows); Escape closes it without closing the screen
        click(session, 220, 20)
        assertTrue(session.mouseScrolled(220.0, 50.0, 0.0, -20.0)) // clamped to (20 - 5 rows) * 14
        frame(session)
        assertEquals(15 * 14.0, session.self.get("ui").get("state").get("dd").get("y").todouble())
        assertEquals(KeyResult.HANDLED, host.escape())
        assertTrue(session.self.get("ui").get("openDropdown").isnil())
        // the list blocks what is below it; a click outside only closes it
        click(session, 220, 20)
        assertTrue(click(session, 20, 15))
        assertEquals(0, g(app, "base").toint())
        assertTrue(session.self.get("ui").get("openDropdown").isnil())
        click(session, 20, 15)
        assertEquals(1, g(app, "base").toint())
        assertTrue(app.alive, host.failures.toString())
    }

    @Test
    fun `slider snaps to its steps, toggle switches`() {
        val (host, app) = kitHost()
        val session = host.shown!!
        val kit = host.extensions.getValue("ui_kit")
        click(session, 10 + 4 + 50, 75) // middle of 108 px (100 px track) -> 5 -> snapped to 4 or 6
        assertTrue(g(app, "level").toint() in setOf(4, 6))
        click(session, 10 + 4 + 100, 75)
        assertEquals(10, g(app, "level").toint())
        click(session, 10, 75)
        assertEquals(0, g(app, "level").toint())
        // dragging: press, move, frame, release
        frame(session, 14, 75)
        session.mouseClicked(14.0, 75.0, 0, none)
        frame(session, 14 + 30, 75)
        assertEquals(4, g(app, "level").toint()) // 30/100*10 = 3 -> on the step-2 grid 1.5 rounds up to 4
        frame(session, 14 + 70, 75)
        assertEquals(8, g(app, "level").toint())
        session.mouseReleased(84.0, 75.0, 0)
        frame(session, 14 + 100, 75) // released: no more changes
        assertEquals(8, g(app, "level").toint())
        click(session, 15, 105)
        assertTrue(g(app, "on").toboolean())
        assertTrue(app.alive, host.failures.toString())
        assertTrue(kit.alive)
    }

    @Test
    fun `pure helpers - snap, scroll clamp, number formats, ellipsis-safe utf8`() {
        val (host, _) = kitHost()
        val kit = host.extensions.getValue("ui_kit")
        val app = host.extensions.getValue("app")
        val k = app.runtime.globals.get("kiln").get("import").call(LuaValue.valueOf("ui_kit"))
        fun num(fn: String, vararg a: Double) = call(app, k.get(fn), *a.map { LuaValue.valueOf(it) }.toTypedArray()).arg1().todouble()
        fun str(fn: String, a: LuaValue) = call(app, k.get(fn), a).arg1().tojstring()
        assertEquals(4.0, num("snap", 5.0, 0.0, 10.0, 4.0))
        assertEquals(10.0, num("snap", 13.0, 0.0, 10.0, 2.0))
        assertEquals(3.0, num("snap", 2.6, 1.0, 64.0, 1.0))
        assertEquals(1.0, num("snap", -5.0, 1.0, 64.0, 1.0))
        assertEquals(0.0, num("clampScroll", -20.0, 1000.0, 100.0))
        assertEquals(900.0, num("clampScroll", 5000.0, 1000.0, 100.0))
        assertEquals(0.0, num("clampScroll", 50.0, 80.0, 100.0))
        assertEquals("999", str("formatCount", LuaValue.valueOf(999)))
        assertEquals("1.2k", str("formatCount", LuaValue.valueOf(1234)))
        assertEquals("1k", str("formatCount", LuaValue.valueOf(1000)))
        assertEquals("12k", str("formatCount", LuaValue.valueOf(12345)))
        assertEquals("1.5M", str("formatCount", LuaValue.valueOf(1_500_000)))
        assertEquals("1.234.567", str("formatNumber", LuaValue.valueOf(1234567)))
        assertEquals("12", str("formatNumber", LuaValue.valueOf(12)))
        assertEquals("äöü abc", str("lower", LuaValue.valueOf("ÄÖÜ ABC")))
        assertEquals("Äp", call(app, k.get("utf8sub"), LuaValue.valueOf("Äpfel"), LuaValue.valueOf(2)).arg1().tojstring())
        assertTrue(kit.alive)
    }

    @Test
    fun `scroll panel clamps, keeps its offset per id and scrolls only when nothing blocks it`() {
        val (host, app) = kitHost()
        val session = host.shown!!
        frame(session)
        repeat(100) { session.mouseScrolled(320.0, 150.0, 0.0, -1.0) }
        frame(session)
        assertEquals(900.0, g(app, "offset").todouble()) // content 1000 - view 100
        session.mouseScrolled(320.0, 150.0, 0.0, 100.0)
        frame(session)
        assertEquals(0.0, g(app, "offset").todouble())
        // the inner button is clickable at its scrolled position and clipped outside the panel
        click(session, 320, 125)
        // dragging the thumb (x 374..380): thumb 10 px high (min 16) at the top; drag down 42 px -> 42 * 900 / 84
        frame(session, 377, 105)
        session.mouseClicked(377.0, 105.0, 0, none)
        frame(session, 377, 147)
        assertEquals(450.0, g(app, "offset").todouble(), 1.0)
        session.mouseReleased(377.0, 147.0, 0)
        // a modal blocks wheel and clicks below it and hides the native field under it
        app.runtime.globals.set("modal", LuaValue.TRUE)
        frame(session)
        frame(session)
        assertTrue(session.textInputs.none { it.id == "under" })
        val before = g(app, "offset").todouble()
        assertTrue(session.mouseScrolled(320.0, 150.0, 0.0, -1.0))
        frame(session)
        assertEquals(before, g(app, "offset").todouble())
        assertTrue(click(session, 20, 15))
        assertEquals(0, g(app, "base").toint())
        // click on the backdrop dismisses (dismiss = true), then the field is back
        click(session, 5, 290)
        assertFalse(g(app, "modal").toboolean())
        frame(session)
        assertTrue(session.textInputs.any { it.id == "under" })
        assertTrue(app.alive, host.failures.toString())
    }

    // ---- budget ---------------------------------------------------------------------------------------------

    @Test
    fun `benchmark - market with 50 offers stays far below the frame budget`() {
        val (host, shop) = loadShop()
        val offers = (1..50).map { offer(it, "Angebot Nummer $it mit langem Namen", "minecraft:emerald", listOf("BLOCKS", "ORES", "FOOD")[it % 3], it * 37, it * 13, "Spieler$it") }
        shop.onChannelMessage("open", state(offers))
        val session = host.shown!!
        session.layout(854, 1400, 2.0) // tall window: all 50 tiles visible at once (worst case)
        val target = CountingTarget()
        repeat(200) { session.render(target, 130 + it % 600, 80 + it % 1200) } // warm-up (JIT)
        val frames = 1_000
        var maxInstructions = 0L
        var sum = 0L
        val start = System.nanoTime()
        for (i in 0 until frames) {
            session.render(target, 130 + i % 600, 80 + (i * 7) % 1200)
            val used = shop.runtime.lastCallInstructions
            maxInstructions = maxOf(maxInstructions, used)
            sum += used
        }
        val millis = (System.nanoTime() - start) / 1e6 / frames
        val budget = shop.runtime.limits.instructionsPerFrame
        val visible = target.items / (frames + 200)
        println(
            "[benchmark] lua shop, 50 offers ($visible items per frame): avg ${sum / frames} / max $maxInstructions instructions " +
                "per frame (budget $budget, ${100 * maxInstructions / budget} %), ${"%.3f".format(millis)} ms per frame (Lua side only)",
        )
        assertTrue(shop.alive, host.failures.toString())
        assertTrue(visible >= 50, "all 50 offers visible ($visible)")
        assertTrue(maxInstructions < budget / 5, "max $maxInstructions instructions per frame")
        assertTrue(millis < 4.0, "$millis ms per frame")
    }
}
