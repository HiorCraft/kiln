package dev.kiln.example.server.shop.lua

import com.google.gson.JsonObject
import dev.kiln.example.server.shop.BuyError
import dev.kiln.example.server.shop.BuyResult
import dev.kiln.example.server.shop.Category
import dev.kiln.example.server.shop.ListingDuration
import dev.kiln.example.server.shop.Market
import dev.kiln.example.server.shop.MarketQuery
import dev.kiln.example.server.shop.Offer
import dev.kiln.example.server.shop.ShopFormat
import dev.kiln.example.server.shop.ShopValidation
import java.util.UUID

/** An item in one of the player's storage slots (0..35), as seen by the server. */
data class SlotContent<I>(
    /** Single-item copy of the stack. */
    val template: I,
    val amount: Int,
    /** Namespaced item id, e.g. `minecraft:oak_log` (what the client sends as `item`). */
    val key: String,
    /** Key path, e.g. `oak_log` (searchable, stored in the offer). */
    val keyPath: String,
    /** Plain, searchable name. */
    val name: String,
)

/** What the controller needs from the game: the player's storage inventory (main thread only). */
interface ShopInventoryAccess<I> {
    /** How many more of [item] fit into [player]'s storage slots. */
    fun freeSpace(player: UUID, item: I): Long

    /** Gives [amount] of [item]; returns how many had to be dropped. */
    fun give(player: UUID, item: I, amount: Int): Int

    /** Content of storage slot [slot] (0..35) or `null` if empty / player offline. */
    fun slot(player: UUID, slot: Int): SlotContent<I>?

    /** How many items similar to [template] (same item and data) the player has. */
    fun countSimilar(player: UUID, template: I): Int

    /** Removes [amount] items similar to [template], starting at [preferredSlot]; `false` (nothing changed) if too few. */
    fun take(player: UUID, template: I, amount: Int, preferredSlot: Int): Boolean
}

/** A completed purchase, for notifying the seller. */
data class Sale<I>(val offer: Offer<I>, val amount: Int, val total: Long, val buyerName: String)

/**
 * Result of a request: the [result] for the requesting client; [changed] = market or balances changed (broadcast new
 * state); [sale] = notify the seller; [items]/[dropped] for chat/toast feedback.
 */
data class ShopOutcome<I>(
    val result: ShopResult,
    val changed: Boolean = false,
    val sale: Sale<I>? = null,
    val item: I? = null,
    val itemAmount: Int = 0,
    val dropped: Int = 0,
)

/** Token bucket per player: [capacity] requests at once, refilled with [perSecond]. Pure (time is passed in). */
class RequestRateLimiter(private val capacity: Double = 6.0, private val perSecond: Double = 4.0) {
    private class Bucket(var tokens: Double, var at: Long)

    private val buckets = HashMap<UUID, Bucket>()

    fun tryAcquire(player: UUID, nowMillis: Long): Boolean {
        val bucket = buckets.getOrPut(player) { Bucket(capacity, nowMillis) }
        val elapsed = (nowMillis - bucket.at).coerceAtLeast(0)
        bucket.tokens = minOf(capacity, bucket.tokens + elapsed * perSecond / 1000.0)
        bucket.at = nowMillis
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    fun forget(player: UUID) {
        buckets.remove(player)
    }
}

/**
 * Server side of the Lua shop, independent of Bukkit: validates every client request strictly against the
 * authoritative [market] and the player's real inventory ([access]) and executes it. The client never decides
 * anything: prices, stock, money, ownership and inventory contents are all checked here.
 */
class LuaShopController<I>(
    val market: Market<I>,
    private val access: ShopInventoryAccess<I>,
    private val limiter: RequestRateLimiter = RequestRateLimiter(),
) {
    private val itemCache = HashMap<Long, String>()

    // ---- state ----------------------------------------------------------------------------------------------

    /**
     * Full state for [player]. [encode] turns an item into its client form (`ExtensionItems.encode`); offers whose
     * item cannot be encoded are left out. [suggest] = suggested category per item id in the player's inventory.
     */
    fun state(player: UUID, now: Long, encode: (I) -> String, open: Boolean, tab: String?, suggest: Map<String, Category>): ShopState {
        var budget = MAX_ITEM_CHARS
        var truncated = false
        fun encoded(offer: Offer<I>): String? {
            val text = itemCache[offer.id] ?: runCatching { encode(offer.item) }.getOrNull()?.also { itemCache[offer.id] = it } ?: return null
            if (text.length > budget) {
                truncated = true
                return null
            }
            budget -= text.length
            return text
        }
        // own offers first (always needed for "Meine Angebote"), then the market, newest first
        val mine = market.offersOf(player).mapNotNull { offer ->
            val item = encoded(offer) ?: return@mapNotNull null
            MyOfferDto(
                offer.id, item, offer.name, offer.itemKey, offer.category.name, offer.pricePerUnit, offer.amount,
                offer.expiresAt?.let { it - now }, offer.isExpired(now),
            )
        }
        val active = market.query(MarketQuery(), now)
        val offers = ArrayList<OfferDto>()
        for (offer in active) {
            if (offers.size >= MAX_OFFERS) {
                truncated = true
                break
            }
            val item = encoded(offer) ?: continue
            offers += OfferDto(
                offer.id, item, offer.name, offer.itemKey, offer.category.name, offer.pricePerUnit, offer.amount,
                offer.sellerName, offer.sellerId == player, offer.createdAt, offer.expiresAt?.let { it - now },
            )
        }
        // forget items of offers that are gone
        if (itemCache.size > market.offers().size + 64) {
            val ids = market.offers().mapTo(HashSet()) { it.id }
            itemCache.keys.retainAll(ids)
        }
        return ShopState(
            open, tab, market.balance(player), market.startBalance, Market.MAX_PRICE,
            Category.entries.map { LabelDto(it.name, it.label) },
            ListingDuration.entries.map { LabelDto(it.name, it.label) },
            offers, mine, suggest.mapValues { it.value.name }, truncated,
        )
    }

    // ---- requests -------------------------------------------------------------------------------------------

    private fun fail(req: Long, action: String, error: String, fields: Map<String, String>? = null, newPrice: Long? = null) =
        ShopOutcome<I>(ShopResult(req, action, ok = false, error = error, fields = fields, newPrice = newPrice))

    private inline fun guarded(player: UUID, msg: JsonObject?, action: String, now: Long, body: (Long) -> ShopOutcome<I>): ShopOutcome<I> {
        val req = RequestFields.req(msg)
        if (!limiter.tryAcquire(player, now)) return fail(req, action, TOO_MANY)
        return body(req)
    }

    fun buy(player: UUID, playerName: String, msg: JsonObject?, now: Long): ShopOutcome<I> = guarded(player, msg, "buy", now) { req ->
        val offerId = RequestFields.long(msg, "offer", 1, Long.MAX_VALUE) ?: return fail(req, "buy", INVALID)
        val price = RequestFields.long(msg, "price", 1, Market.MAX_PRICE) ?: return fail(req, "buy", INVALID)
        val amount = RequestFields.int(msg, "amount", 1, Int.MAX_VALUE)
        val offer = market.offer(offerId)
        if (amount == null) {
            return fail(req, "buy", offer?.let { BuyError.InvalidAmount(it.amount).message } ?: BuyError.OfferGone.message)
        }
        val space = offer?.let { access.freeSpace(player, it.item) } ?: 0L
        when (val result = market.buy(player, offerId, amount, now, space, price)) {
            is BuyResult.Failure -> {
                val error = result.error
                fail(req, "buy", error.message, newPrice = (error as? BuyError.PriceChanged)?.newPrice)
            }
            is BuyResult.Success -> {
                val bought = result.offer
                val dropped = access.give(player, bought.item, result.amount)
                ShopOutcome(
                    ShopResult(req, "buy", ok = true, message = "Gekauft: ${ShopFormat.number(result.amount.toLong())}× ${bought.name} für ${ShopFormat.money(result.total)}"),
                    changed = true, sale = Sale(bought, result.amount, result.total, playerName), item = bought.item,
                    itemAmount = result.amount, dropped = dropped,
                )
            }
        }
    }

    fun sell(player: UUID, playerName: String, msg: JsonObject?, now: Long): ShopOutcome<I> = guarded(player, msg, "sell", now) { req ->
        val slot = RequestFields.int(msg, "slot", 0, STORAGE_SLOTS - 1) ?: return fail(req, "sell", INVALID)
        val itemKey = RequestFields.string(msg, "item", 128) ?: return fail(req, "sell", INVALID)
        val duration = RequestFields.string(msg, "duration", 16)?.let { name -> ListingDuration.entries.find { it.name == name } }
            ?: return fail(req, "sell", INVALID)
        val category = RequestFields.string(msg, "category", 16)?.let { name -> Category.listable.find { it.name == name } }
        val notify = RequestFields.boolean(msg, "notify") ?: false
        // numbers: wrong types are an invalid request, values out of range are field errors (like the form)
        val amountText = numberText(msg, "amount") ?: return fail(req, "sell", INVALID)
        val priceText = numberText(msg, "price") ?: return fail(req, "sell", INVALID)

        val content = access.slot(player, slot)?.takeIf { it.key == itemKey }
        val available = content?.let { access.countSimilar(player, it.template) } ?: 0
        val validation = ShopValidation.listing(content != null, available, amountText, priceText, category)
        val errors = LinkedHashMap(validation.errors)
        if (content == null) errors["item"] = "Das Item ist nicht mehr in diesem Slot."
        val amount = validation.amount
        val price = validation.price
        if (errors.isNotEmpty() || content == null || amount == null || price == null || category == null) {
            return fail(req, "sell", errors.values.first(), errors)
        }
        if (!access.take(player, content.template, amount, slot)) {
            return fail(req, "sell", "Nicht mehr genug Items im Inventar.", mapOf("amount" to "Nicht mehr genug Items im Inventar."))
        }
        val offer = market.createOffer(content.template, content.name, content.keyPath, category, price, amount, player, playerName, now, duration, notify)
        ShopOutcome(
            ShopResult(req, "sell", ok = true, message = "Angebot eingestellt: ${ShopFormat.number(amount.toLong())}× ${offer.name} à ${ShopFormat.money(price)}"),
            changed = true, item = content.template, itemAmount = amount,
        )
    }

    fun withdraw(player: UUID, msg: JsonObject?, now: Long): ShopOutcome<I> = guarded(player, msg, "withdraw", now) { req ->
        val offerId = RequestFields.long(msg, "offer", 1, Long.MAX_VALUE) ?: return fail(req, "withdraw", INVALID)
        val offer = market.withdraw(player, offerId) ?: return fail(req, "withdraw", "Das Angebot existiert nicht mehr.")
        val dropped = access.give(player, offer.item, offer.amount)
        val where = if (dropped > 0) " ($dropped gedroppt, Inventar voll)" else " zurück im Inventar"
        ShopOutcome(
            ShopResult(req, "withdraw", ok = true, message = "${ShopFormat.number(offer.amount.toLong())}× ${offer.name}$where"),
            changed = true, item = offer.item, itemAmount = offer.amount, dropped = dropped,
        )
    }

    fun reprice(player: UUID, msg: JsonObject?, now: Long): ShopOutcome<I> = guarded(player, msg, "reprice", now) { req ->
        val offerId = RequestFields.long(msg, "offer", 1, Long.MAX_VALUE) ?: return fail(req, "reprice", INVALID)
        val priceText = numberText(msg, "price") ?: return fail(req, "reprice", INVALID)
        ShopValidation.price(priceText)?.let { return fail(req, "reprice", it, mapOf("price" to it)) }
        val price = priceText.toLong()
        if (!market.changePrice(player, offerId, price, now)) return fail(req, "reprice", "Das Angebot kann nicht mehr geändert werden.")
        val offer = market.offer(offerId)
        ShopOutcome(ShopResult(req, "reprice", ok = true, message = "Neuer Preis für ${offer?.name ?: "dein Angebot"}: ${ShopFormat.money(price)}/Stk."), changed = true)
    }

    fun forget(player: UUID) = limiter.forget(player)

    /**
     * Text of a JSON number field for the form validation (which reports range errors per field): integral numbers
     * as digits, fractions as their text (rejected there), `null` for missing fields or other types.
     */
    private fun numberText(msg: JsonObject?, name: String): String? {
        val e = msg?.get(name) ?: return null
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) return null
        val d = e.asDouble
        if (d.isNaN() || d.isInfinite()) return null
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    companion object {
        const val STORAGE_SLOTS = 36
        const val MAX_OFFERS = 400

        /** Item text per state message (the whole message must stay below 262 144 characters). */
        const val MAX_ITEM_CHARS = 200_000
        const val INVALID = "Ungültige Anfrage."
        const val TOO_MANY = "Zu viele Anfragen – bitte kurz warten."
    }
}
