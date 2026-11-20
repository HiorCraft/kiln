package dev.kiln.example.server.shop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class MarketTest {
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val now = 1_000_000L

    private fun market() = Market<String>()

    private fun Market<String>.sell(
        item: String, price: Long, amount: Int, seller: UUID? = alice, createdAt: Long = now,
        duration: ListingDuration? = ListingDuration.H24, category: Category = Category.OTHER,
    ) = createOffer(item, item, item, category, price, amount, seller, seller?.toString() ?: "Server", createdAt, duration)

    @Test
    fun `start balance is 1000`() {
        assertEquals(1_000L, market().balance(bob))
    }

    @Test
    fun `buying moves money and reduces the offer`() {
        val m = market()
        val offer = m.sell("diamond", 100, 10)
        val result = m.buy(bob, offer.id, 3, now, freeSpace = 64)
        assertInstanceOf(BuyResult.Success::class.java, result)
        result as BuyResult.Success
        assertEquals(300, result.total)
        assertEquals(7, result.remaining)
        assertEquals(700, m.balance(bob))
        assertEquals(1_300, m.balance(alice))
        assertEquals(7, m.offer(offer.id)!!.amount)
    }

    @Test
    fun `buying everything removes the offer`() {
        val m = market()
        val offer = m.sell("diamond", 10, 5)
        m.buy(bob, offer.id, 5, now, 64)
        assertNull(m.offer(offer.id))
        assertEquals(BuyError.OfferGone, (m.buy(bob, offer.id, 1, now, 64) as BuyResult.Failure).error)
    }

    @Test
    fun `server offers take the money out of the economy`() {
        val m = market()
        val offer = m.sell("bread", 4, 64, seller = null, duration = null)
        m.buy(bob, offer.id, 10, now, 64)
        assertEquals(960, m.balance(bob))
        assertEquals(54, m.offer(offer.id)!!.amount)
    }

    @Test
    fun `purchase checks`() {
        val m = market()
        val offer = m.sell("diamond", 300, 5)
        assertEquals(BuyError.OwnOffer, m.checkBuy(alice, offer.id, 1, now, 64))
        assertEquals(BuyError.InvalidAmount(5), m.checkBuy(bob, offer.id, 0, now, 64))
        assertEquals(BuyError.NotEnoughStock(5), m.checkBuy(bob, offer.id, 6, now, 64))
        assertEquals(BuyError.InsufficientFunds(200), m.checkBuy(bob, offer.id, 4, now, 64))
        assertEquals(BuyError.NoSpace(2), m.checkBuy(bob, offer.id, 3, now, 2))
        assertEquals(BuyError.NoSpace(0), m.checkBuy(bob, offer.id, 1, now, 0))
        assertEquals(BuyError.PriceChanged(300), m.checkBuy(bob, offer.id, 1, now, 64, expectedPrice = 250))
        assertNull(m.checkBuy(bob, offer.id, 3, now, 3, expectedPrice = 300))
    }

    @Test
    fun `failed purchase changes nothing`() {
        val m = market()
        val offer = m.sell("diamond", 300, 5)
        val result = m.buy(bob, offer.id, 4, now, 64)
        assertInstanceOf(BuyResult.Failure::class.java, result)
        assertEquals(1_000, m.balance(bob))
        assertEquals(1_000, m.balance(alice))
        assertEquals(5, m.offer(offer.id)!!.amount)
    }

    @Test
    fun `expired offers cannot be bought but can be withdrawn`() {
        val m = market()
        val offer = m.sell("diamond", 1, 5, duration = ListingDuration.H1)
        val later = now + ListingDuration.H1.millis
        assertEquals(BuyError.OfferGone, m.checkBuy(bob, offer.id, 1, later, 64))
        assertTrue(m.query(MarketQuery(), later).isEmpty())
        assertFalse(m.changePrice(alice, offer.id, 5, later))
        assertNull(m.withdraw(bob, offer.id))
        assertEquals(offer, m.withdraw(alice, offer.id))
        assertNull(m.offer(offer.id))
    }

    @Test
    fun `price changes only by the owner and in range`() {
        val m = market()
        val offer = m.sell("diamond", 10, 5)
        assertFalse(m.changePrice(bob, offer.id, 20, now))
        assertFalse(m.changePrice(alice, offer.id, 0, now))
        assertFalse(m.changePrice(alice, offer.id, Market.MAX_PRICE + 1, now))
        assertTrue(m.changePrice(alice, offer.id, 20, now))
        assertEquals(20, m.offer(offer.id)!!.pricePerUnit)
    }

    @Test
    fun `offersOf lists own offers newest first`() {
        val m = market()
        val a = m.sell("a", 1, 1, createdAt = now)
        m.sell("b", 1, 1, seller = bob)
        val c = m.sell("c", 1, 1, createdAt = now + 5)
        assertEquals(listOf(c.id, a.id), m.offersOf(alice).map { it.id })
    }
}
