package dev.kiln.example.server.shop

import java.util.UUID

/**
 * In-memory market: offers plus a demo balance per player. Not thread-safe: all calls happen on the server main
 * thread (Paper event/command/screen handlers), which also serializes concurrent purchases.
 */
class Market<I>(val startBalance: Long = START_BALANCE) {
    private val offers = LinkedHashMap<Long, Offer<I>>()
    private val balances = HashMap<UUID, Long>()
    private var nextId = 1L

    fun offers(): List<Offer<I>> = offers.values.toList()
    fun offer(id: Long): Offer<I>? = offers[id]
    fun offersOf(owner: UUID): List<Offer<I>> = offers.values.filter { it.sellerId == owner }.sortedByDescending { it.createdAt }
    fun query(query: MarketQuery, now: Long): List<Offer<I>> = OfferFilter.apply(offers.values, query, now)

    fun balance(player: UUID): Long = balances[player] ?: startBalance

    private fun credit(player: UUID, amount: Long) {
        balances[player] = Math.addExact(balance(player), amount)
    }

    fun createOffer(
        item: I,
        name: String,
        itemKey: String,
        category: Category,
        pricePerUnit: Long,
        amount: Int,
        sellerId: UUID?,
        sellerName: String,
        now: Long,
        duration: ListingDuration?,
        notifyOnSale: Boolean = false,
    ): Offer<I> {
        require(category != Category.ALL) { "Offers need a concrete category" }
        require(pricePerUnit in 1..MAX_PRICE) { "Price out of range: $pricePerUnit" }
        require(amount >= 1) { "Amount must be positive: $amount" }
        val offer = Offer(
            nextId++, item, name, itemKey, category, pricePerUnit, amount, sellerId, sellerName, now,
            duration?.let { now + it.millis }, notifyOnSale,
        )
        offers[offer.id] = offer
        return offer
    }

    /**
     * Checks a purchase of [amount] from offer [offerId] by [buyer], whose inventory has room for [freeSpace] items
     * of that kind. [expectedPrice] is the unit price the buyer saw (`null` = do not check).
     */
    fun checkBuy(buyer: UUID, offerId: Long, amount: Int, now: Long, freeSpace: Long, expectedPrice: Long? = null): BuyError? {
        val offer = offers[offerId]?.takeIf { it.isActive(now) } ?: return BuyError.OfferGone
        if (offer.sellerId == buyer) return BuyError.OwnOffer
        if (expectedPrice != null && expectedPrice != offer.pricePerUnit) return BuyError.PriceChanged(offer.pricePerUnit)
        if (amount < 1) return BuyError.InvalidAmount(offer.amount)
        if (amount > offer.amount) return BuyError.NotEnoughStock(offer.amount)
        val total = totalPrice(offer.pricePerUnit, amount)
        val balance = balance(buyer)
        if (total > balance) return BuyError.InsufficientFunds(total - balance)
        if (freeSpace < amount) return BuyError.NoSpace(freeSpace)
        return null
    }

    /** Checks and, if possible, executes a purchase: money moves to the seller (server offers: money is gone). */
    fun buy(buyer: UUID, offerId: Long, amount: Int, now: Long, freeSpace: Long, expectedPrice: Long? = null): BuyResult<I> {
        checkBuy(buyer, offerId, amount, now, freeSpace, expectedPrice)?.let { return BuyResult.Failure(it) }
        val offer = offers.getValue(offerId)
        val total = totalPrice(offer.pricePerUnit, amount)
        credit(buyer, -total)
        offer.sellerId?.let { credit(it, total) }
        val remaining = offer.amount - amount
        if (remaining == 0) offers.remove(offerId) else offers[offerId] = offer.copy(amount = remaining)
        return BuyResult.Success(offer, amount, total, remaining)
    }

    /** Removes an own offer (also expired ones) and returns it so the items can be given back; `null` if not allowed. */
    fun withdraw(owner: UUID, offerId: Long): Offer<I>? {
        val offer = offers[offerId]?.takeIf { it.sellerId == owner } ?: return null
        offers.remove(offerId)
        return offer
    }

    /** Changes the unit price of an own, active offer. */
    fun changePrice(owner: UUID, offerId: Long, newPrice: Long, now: Long): Boolean {
        val offer = offers[offerId]?.takeIf { it.sellerId == owner && it.isActive(now) } ?: return false
        if (newPrice !in 1..MAX_PRICE) return false
        offers[offerId] = offer.copy(pricePerUnit = newPrice)
        return true
    }

    companion object {
        const val START_BALANCE = 1_000L
        const val MAX_PRICE = 1_000_000L

        fun totalPrice(pricePerUnit: Long, amount: Int): Long = Math.multiplyExact(pricePerUnit, amount.toLong())
    }
}
