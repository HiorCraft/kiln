package dev.kiln.example.server.shop.lua

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.kiln.example.server.shop.Category
import dev.kiln.example.server.shop.ListingDuration
import dev.kiln.example.server.shop.Market
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Channel handlers of the Lua shop: strict request validation and the market actions behind them. */
class LuaShopControllerTest {

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val now = 1_000_000L
    private val market = Market<String>()
    private val inventory = FakeInventory()
    private val controller = LuaShopController(market, inventory, RequestRateLimiter(capacity = 1000.0, perSecond = 1000.0))

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    private fun bobsDiamonds(price: Long = 10, amount: Int = 32) =
        market.createOffer("diamond", "Diamond", "diamond", Category.ORES, price, amount, bob, "Bob", now - 1000, ListingDuration.H24, notifyOnSale = true)

    // ---- buy ------------------------------------------------------------------------------------------------

    @Test
    fun `buy moves money and items and reports the sale`() {
        val offer = bobsDiamonds()
        val out = controller.buy(alice, "Alice", json("""{"req":7,"offer":${offer.id},"amount":5,"price":10}"""), now)
        assertTrue(out.result.ok, out.result.error)
        assertEquals(7, out.result.req)
        assertEquals("buy", out.result.action)
        assertTrue(out.changed)
        assertEquals(Market.START_BALANCE - 50, market.balance(alice))
        assertEquals(Market.START_BALANCE + 50, market.balance(bob))
        assertEquals(5, inventory.countSimilar(alice, "diamond"))
        assertEquals(27, market.offer(offer.id)!!.amount)
        assertEquals("Alice", out.sale!!.buyerName)
        assertEquals(50, out.sale!!.total)
    }

    @Test
    fun `buy rejects invalid requests and every rule of the market`() {
        val offer = bobsDiamonds(price = 100, amount = 20)
        fun error(body: String, player: UUID = alice): String? {
            val out = controller.buy(player, "x", json(body), now)
            assertFalse(out.result.ok)
            assertFalse(out.changed)
            return out.result.error
        }
        val id = offer.id
        // malformed: wrong types, fractions, out of range, missing
        assertEquals(LuaShopController.INVALID, error("""{"offer":"$id","amount":1,"price":100}"""))
        assertEquals(LuaShopController.INVALID, error("""{"offer":$id,"amount":1}"""))
        assertEquals(LuaShopController.INVALID, error("""{"offer":$id,"amount":1,"price":100.5}"""))
        assertEquals(LuaShopController.INVALID, error("""{"offer":$id,"amount":1,"price":1e30}"""))
        assertEquals(LuaShopController.INVALID, error("""{"offer":-1,"amount":1,"price":100}"""))
        assertEquals("Bitte eine Menge von 1 bis 20 wählen.", error("""{"offer":$id,"amount":0,"price":100}"""))
        assertEquals("Bitte eine Menge von 1 bis 20 wählen.", error("""{"offer":$id,"amount":2.5,"price":100}"""))
        assertEquals("Bitte eine Menge von 1 bis 20 wählen.", error("""{"offer":$id,"amount":99999999999,"price":100}"""))
        // market rules
        assertEquals("Das Angebot ist nicht mehr verfügbar.", error("""{"offer":999,"amount":1,"price":100}"""))
        assertEquals("Das ist dein eigenes Angebot.", error("""{"offer":$id,"amount":1,"price":100}""", bob))
        assertEquals("Nur noch 20 Stück verfügbar.", error("""{"offer":$id,"amount":21,"price":100}"""))
        assertEquals("Nicht genug Geld (es fehlen 1.000 Taler).", error("""{"offer":$id,"amount":20,"price":100}"""))
        val changed = controller.buy(alice, "x", json("""{"offer":$id,"amount":1,"price":90}"""), now)
        assertEquals(100L, changed.result.newPrice)
        // full inventory
        val inv = inventory.of(alice)
        for (i in inv.indices) inv[i] = "dirt" to 64
        assertEquals("Dein Inventar ist voll.", error("""{"offer":$id,"amount":1,"price":100}"""))
        assertEquals(Market.START_BALANCE, market.balance(alice))
        assertEquals(20, market.offer(id)!!.amount)
        // expired offers cannot be bought
        assertEquals("Das Angebot ist nicht mehr verfügbar.", controller.buy(alice, "x", json("""{"offer":$id,"amount":1,"price":100}"""), now + ListingDuration.H24.millis).result.error)
        // not even an object
        assertFalse(controller.buy(alice, "x", null, now).result.ok)
    }

    // ---- sell -----------------------------------------------------------------------------------------------

    @Test
    fun `sell takes the items from the chosen slot first and creates the offer`() {
        val inv = inventory.of(alice)
        inv[0] = "oak_log" to 10
        inv[9] = "oak_log" to 64
        inv[10] = "diamond" to 3
        val out = controller.sell(alice, "Alice", json("""{"req":3,"slot":9,"item":"minecraft:oak_log","amount":70,"price":2,"duration":"H12","category":"BLOCKS","notify":true}"""), now)
        assertTrue(out.result.ok, out.result.error)
        assertTrue(out.changed)
        assertNull(inv[9])
        assertEquals("oak_log" to 4, inv[0])
        val offer = market.offersOf(alice).single()
        assertEquals(70, offer.amount)
        assertEquals(2, offer.pricePerUnit)
        assertEquals(Category.BLOCKS, offer.category)
        assertEquals(now + ListingDuration.H12.millis, offer.expiresAt)
        assertTrue(offer.notifyOnSale)
        assertEquals("Alice", offer.sellerName)
    }

    @Test
    fun `sell validates slot, item, amount, price, duration and category against the real inventory`() {
        val inv = inventory.of(alice)
        inv[5] = "diamond" to 3
        fun sell(body: String) = controller.sell(alice, "Alice", json(body), now).result
        val base = """"slot":5,"item":"minecraft:diamond","duration":"H24","category":"ORES""""
        // malformed requests
        assertEquals(LuaShopController.INVALID, sell("""{"slot":36,"item":"minecraft:diamond","amount":1,"price":1,"duration":"H24","category":"ORES"}""").error)
        assertEquals(LuaShopController.INVALID, sell("""{"slot":5,"item":"minecraft:diamond","amount":"1","price":1,"duration":"H24","category":"ORES"}""").error)
        assertEquals(LuaShopController.INVALID, sell("""{"slot":5,"item":"minecraft:diamond","amount":1,"price":1,"duration":"Y1","category":"ORES"}""").error)
        assertEquals(LuaShopController.INVALID, sell("""{"slot":5,"amount":1,"price":1,"duration":"H24","category":"ORES"}""").error)
        // form errors per field
        assertEquals(mapOf("amount" to "Du hast nur 3 Stück."), sell("""{$base,"amount":4,"price":5}""").fields)
        assertEquals(mapOf("amount" to "Menge muss eine ganze Zahl ab 1 sein."), sell("""{$base,"amount":0,"price":5}""").fields)
        assertEquals(mapOf("amount" to "Menge muss eine ganze Zahl ab 1 sein."), sell("""{$base,"amount":1.5,"price":5}""").fields)
        assertEquals(mapOf("price" to "Preis muss zwischen 1 und 1.000.000 liegen."), sell("""{$base,"amount":1,"price":1000001}""").fields)
        assertEquals("Kategorie wählen.", sell("""{"slot":5,"item":"minecraft:diamond","duration":"H24","category":"ALL","amount":1,"price":5}""").fields!!["category"])
        // the slot must still hold what the client saw
        assertEquals("Das Item ist nicht mehr in diesem Slot.", sell("""{"slot":5,"item":"minecraft:emerald","duration":"H24","category":"ORES","amount":1,"price":5}""").fields!!["item"])
        assertEquals("Das Item ist nicht mehr in diesem Slot.", sell("""{"slot":6,"item":"minecraft:diamond","duration":"H24","category":"ORES","amount":1,"price":5}""").fields!!["item"])
        assertTrue(market.offers().isEmpty())
        assertEquals("diamond" to 3, inv[5])
    }

    // ---- own offers -----------------------------------------------------------------------------------------

    @Test
    fun `withdraw and reprice only work on own offers`() {
        val offer = bobsDiamonds(amount = 100)
        // Alice cannot touch Bob's offer
        assertEquals("Das Angebot existiert nicht mehr.", controller.withdraw(alice, json("""{"offer":${offer.id}}"""), now).result.error)
        assertEquals("Das Angebot kann nicht mehr geändert werden.", controller.reprice(alice, json("""{"offer":${offer.id},"price":1}"""), now).result.error)
        assertEquals(10, market.offer(offer.id)!!.pricePerUnit)
        // Bob changes the price (range checked) and withdraws: items back, overflow dropped
        assertEquals("Preis muss zwischen 1 und 1.000.000 liegen.", controller.reprice(bob, json("""{"offer":${offer.id},"price":0}"""), now).result.error)
        assertEquals(LuaShopController.INVALID, controller.reprice(bob, json("""{"offer":${offer.id},"price":"5"}"""), now).result.error)
        val repriced = controller.reprice(bob, json("""{"req":2,"offer":${offer.id},"price":12}"""), now)
        assertTrue(repriced.result.ok && repriced.changed)
        assertEquals(12, market.offer(offer.id)!!.pricePerUnit)
        val inv = inventory.of(bob)
        for (i in 1 until 36) inv[i] = "dirt" to 64
        val out = controller.withdraw(bob, json("""{"offer":${offer.id}}"""), now)
        assertTrue(out.result.ok)
        assertEquals(36, out.dropped) // one free slot of 64
        assertEquals("diamond" to 64, inv[0])
        assertNull(market.offer(offer.id))
        // expired offers can be withdrawn but not repriced
        val old = bobsDiamonds()
        val later = now + ListingDuration.H24.millis
        assertFalse(controller.reprice(bob, json("""{"offer":${old.id},"price":3}"""), later).result.ok)
        assertTrue(controller.withdraw(bob, json("""{"offer":${old.id}}"""), later).result.ok)
    }

    // ---- rate limit -----------------------------------------------------------------------------------------

    @Test
    fun `requests are rate limited per player`() {
        val limited = LuaShopController(market, inventory, RequestRateLimiter(capacity = 3.0, perSecond = 2.0))
        val request = json("""{"offer":999}""")
        repeat(3) { assertEquals("Das Angebot existiert nicht mehr.", limited.withdraw(alice, request, now).result.error) }
        assertEquals(LuaShopController.TOO_MANY, limited.withdraw(alice, request, now).result.error)
        assertEquals("Das Angebot existiert nicht mehr.", limited.withdraw(bob, request, now).result.error, "other players are not affected")
        assertEquals(LuaShopController.TOO_MANY, limited.withdraw(alice, request, now + 400).result.error)
        assertEquals("Das Angebot existiert nicht mehr.", limited.withdraw(alice, request, now + 500).result.error, "refilled after 500 ms")
    }

    // ---- state / JSON ---------------------------------------------------------------------------------------

    @Test
    fun `state JSON has the fields the Lua shop reads`() {
        val offer = bobsDiamonds()
        market.createOffer("bread", "Bread", "bread", Category.FOOD, 4, 64, null, "Server", now - 5000, null)
        val mine = market.createOffer("glass", "Glass", "glass", Category.BLOCKS, 3, 10, alice, "Alice", now - 10, ListingDuration.H1)
        val expired = market.createOffer("stone", "Stone", "stone", Category.BLOCKS, 1, 5, alice, "Alice", now - ListingDuration.H1.millis - 1, ListingDuration.H1)
        val state = controller.state(alice, now, { "item:$it" }, open = true, tab = "sell", suggest = mapOf("minecraft:oak_log" to Category.BLOCKS))
        val json = JsonParser.parseString(Gson().toJson(state)).asJsonObject
        assertTrue(json["open"].asBoolean)
        assertEquals("sell", json["tab"].asString)
        assertEquals(Market.START_BALANCE, json["balance"].asLong)
        assertEquals(Market.MAX_PRICE, json["maxPrice"].asLong)
        assertEquals("ALL", json.getAsJsonArray("categories")[0].asJsonObject["id"].asString)
        assertEquals("1 Stunde", json.getAsJsonArray("durations")[0].asJsonObject["label"].asString)
        assertEquals("BLOCKS", json.getAsJsonObject("suggest")["minecraft:oak_log"].asString)
        val offers = json.getAsJsonArray("offers").map { it.asJsonObject }
        assertEquals(listOf(mine.id, offer.id, offers[2]["id"].asLong), offers.map { it["id"].asLong }) // newest first, expired left out
        val diamonds = offers[1]
        assertEquals("item:diamond", diamonds["item"].asString)
        assertEquals("Diamond", diamonds["name"].asString)
        assertEquals("diamond", diamonds["key"].asString)
        assertEquals("ORES", diamonds["cat"].asString)
        assertEquals(10, diamonds["price"].asLong)
        assertEquals(32, diamonds["amount"].asInt)
        assertEquals("Bob", diamonds["seller"].asString)
        assertFalse(diamonds["own"].asBoolean)
        assertEquals(now - 1000, diamonds["created"].asLong)
        assertEquals(ListingDuration.H24.millis - 1000, diamonds["expiresIn"].asLong)
        assertTrue(offers[0]["own"].asBoolean)
        assertFalse(offers[2].has("expiresIn"), "server offers never expire (null is omitted)")
        val mineJson = json.getAsJsonArray("mine").map { it.asJsonObject }
        assertEquals(setOf(mine.id, expired.id), mineJson.map { it["id"].asLong }.toSet())
        assertTrue(mineJson.single { it["id"].asLong == expired.id }["expired"].asBoolean)
        assertFalse(json["truncated"].asBoolean)

        // results: nulls are omitted
        val result = JsonParser.parseString(Gson().toJson(ShopResult(4, "buy", ok = false, error = "x", newPrice = 5))).asJsonObject
        assertEquals(setOf("req", "action", "ok", "error", "newPrice"), result.keySet())
    }

    @Test
    fun `state stays within the message limit and skips items that cannot be encoded`() {
        repeat(30) { market.createOffer("item$it", "Item $it", "item$it", Category.OTHER, 1, 1, bob, "Bob", now - it, null) }
        val big = controller.state(alice, now, { "x".repeat(10_000) }, open = false, tab = null, suggest = emptyMap())
        assertEquals(LuaShopController.MAX_ITEM_CHARS / 10_000, big.offers.size)
        assertTrue(big.truncated)
        // item texts are cached per offer: a fresh controller encodes again
        val failing = LuaShopController(market, inventory).state(alice, now, { if (it == "item3") error("too large") else it }, open = false, tab = null, suggest = emptyMap())
        assertEquals(29, failing.offers.size)
        assertTrue(Gson().toJson(big).length < 262_144)
    }

    @Test
    fun `request fields are strict`() {
        val o = json("""{"a":5,"b":5.0,"c":5.5,"d":"5","e":true,"f":1e30,"g":null,"h":[1],"s":"ok","t":"a\u0001b","req":12}""")
        assertEquals(5L, RequestFields.long(o, "a", 0, 10))
        assertEquals(5L, RequestFields.long(o, "b", 0, 10))
        assertNull(RequestFields.long(o, "c", 0, 10))
        assertNull(RequestFields.long(o, "d", 0, 10))
        assertNull(RequestFields.long(o, "e", 0, 10))
        assertNull(RequestFields.long(o, "f", 0, Long.MAX_VALUE))
        assertNull(RequestFields.long(o, "g", 0, 10))
        assertNull(RequestFields.long(o, "h", 0, 10))
        assertNull(RequestFields.long(o, "a", 6, 10))
        assertNull(RequestFields.long(o, "missing", 0, 10))
        assertEquals("ok", RequestFields.string(o, "s", 2))
        assertNull(RequestFields.string(o, "s", 1))
        assertNull(RequestFields.string(o, "t", 10))
        assertNull(RequestFields.string(o, "a", 10))
        assertEquals(true, RequestFields.boolean(o, "e"))
        assertNull(RequestFields.boolean(o, "s"))
        assertEquals(12L, RequestFields.req(o))
        assertEquals(0L, RequestFields.req(json("{}")))
    }
}
