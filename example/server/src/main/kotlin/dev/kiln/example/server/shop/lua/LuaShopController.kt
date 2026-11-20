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
        val mine = market.offersOf(player).mapNotNull { offer -> encoded(offer)?.let { myOfferDto(offer, it, now) } }
        val offers = ArrayList<OfferDto>()
        for (offer in market.query(MarketQuery(), now)) {
            if (offers.size >= MAX_OFFERS) {
                truncated = true
                break
            }
            val item = encoded(offer) ?: continue
            offers += offerDto(offer, item, player, now)
        }
        pruneItemCache()
        return ShopState(
            open, tab, market.balance(player), market.startBalance, Market.MAX_PRICE,
            Category.entries.map { LabelDto(it.name, it.label) },
            ListingDuration.entries.map { LabelDto(it.name, it.label) },
            offers, mine, suggest.mapValues { it.value.name }, truncated,
        )
    }

    private fun myOfferDto(offer: Offer<I>, item: String, now: Long) = MyOfferDto(
        offer.id, item, offer.name, offer.itemKey, offer.category.name, offer.pricePerUnit, offer.amount,
        offer.expiresAt?.let { it - now }, offer.isExpired(now),
    )

    private fun offerDto(offer: Offer<I>, item: String, player: UUID, now: Long) = OfferDto(
        offer.id, item, offer.name, offer.itemKey, offer.category.name, offer.pricePerUnit, offer.amount,
        offer.sellerName, offer.sellerId == player, offer.createdAt, offer.expiresAt?.let { it - now },
    )

    /** Forgets the encoded items of offers that are gone (once enough of them piled up). */
    private fun pruneItemCache() {
        if (itemCache.size <= market.offers().size + ITEM_CACHE_SLACK) return
        val ids = market.offers().mapTo(HashSet()) { it.id }
        itemCache.keys.retainAll(ids)
    }

    // ---- requests -------------------------------------------------------------------------------------------

    private fun fail(req: Long, action: String, error: String, fields: Map<String, String>? = null, newPrice: Long? = null) =
        ShopOutcome<I>(ShopResult(req, action, ok = false, error = error, fields = fields, newPrice = newPrice))

    private inline fun guarded(player: UUID, msg: JsonObject?, action: String, now: Long, body: (Long) -> ShopOutcome<I>): ShopOutcome<I> {
        val req = RequestFields.req(msg)
        if (!limiter.tryAcquire(player, now)) return fail(req, action, TOO_MANY)
        return body(req)
    }

    private fun offerId(msg: JsonObject?): Long? = RequestFields.long(msg, "offer", 1, Long.MAX_VALUE)

    fun buy(player: UUID, playerName: String, msg: JsonObject?, now: Long): ShopOutcome<I> = guarded(player, msg, "buy", now) { req ->
        val offerId = offerId(msg) ?: return fail(req, "buy", INVALID)
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
                    changed = true, sale = Sale(bought, result.amount, result.total, playerName), dropped = dropped,
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
            changed = true,
        )
    }

    fun withdraw(player: UUID, msg: JsonObject?, now: Long): ShopOutcome<I> = guarded(player, msg, "withdraw", now) { req ->
        val offerId = offerId(msg) ?: return fail(req, "withdraw", INVALID)
        val offer = market.withdraw(player, offerId) ?: return fail(req, "withdraw", "Das Angebot existiert nicht mehr.")
        val dropped = access.give(player, offer.item, offer.amount)
        val where = if (dropped > 0) " ($dropped gedroppt, Inventar voll)" else " zurück im Inventar"
        ShopOutcome(
            ShopResult(req, "withdraw", ok = true, message = "${ShopFormat.number(offer.amount.toLong())}× ${offer.name}$where"),
            changed = true, dropped = dropped,
        )
    }

    fun reprice(player: UUID, msg: JsonObject?, now: Long): ShopOutcome<I> = guarded(player, msg, "reprice", now) { req ->
        val offerId = offerId(msg) ?: return fail(req, "reprice", INVALID)
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
        return if (d == Math.floor(d) && Math.abs(d) < EXACT_INTEGER_LIMIT) d.toLong().toString() else d.toString()
    }

    companion object {
        const val STORAGE_SLOTS = 36
        const val MAX_OFFERS = 400

        /** Item text per state message (the whole message must stay below 262 144 characters). */
        const val MAX_ITEM_CHARS = 200_000

        /** Encoded items of this many gone offers may stay cached before the cache is pruned. */
        private const val ITEM_CACHE_SLACK = 64

        /** Below this magnitude a whole double converts to the same long (no rounding, no exponent notation). */
        private const val EXACT_INTEGER_LIMIT = 1e15
        const val INVALID = "Ungültige Anfrage."
        const val TOO_MANY = "Zu viele Anfragen – bitte kurz warten."
    }
}
