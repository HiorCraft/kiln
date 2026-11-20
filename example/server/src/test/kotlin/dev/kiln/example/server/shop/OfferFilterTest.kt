package dev.kiln.example.server.shop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class OfferFilterTest {
    private val now = 10_000L
    private val hans = UUID.randomUUID()

    private var nextId = 1L
    private fun offer(
        name: String, price: Long, category: Category, createdAt: Long, amount: Int = 1,
        seller: String = "Server", expiresAt: Long? = null, key: String = name.lowercase().replace(' ', '_'),
    ) = Offer(nextId++, name, name, key, category, price, amount, if (seller == "Server") null else hans, seller, createdAt, expiresAt)

    private val offers = listOf(
        offer("Oak Log", 2, Category.BLOCKS, 100),
        offer("Diamond", 120, Category.ORES, 300),
        offer("Iron Pickaxe", 45, Category.TOOLS, 200, seller = "Hans"),
        offer("Bread", 4, Category.FOOD, 400),
        offer("bread crumbs", 4, Category.FOOD, 500, key = "custom_thing"),
        offer("Old Diamond", 1, Category.ORES, 50, expiresAt = now),
        offer("Sold Out", 1, Category.OTHER, 600, amount = 0),
    )

    private fun names(query: MarketQuery) = OfferFilter.apply(offers, query, now).map { it.name }

    @Test
    fun `newest first by default and inactive offers hidden`() {
        assertEquals(listOf("bread crumbs", "Bread", "Diamond", "Iron Pickaxe", "Oak Log"), names(MarketQuery()))
    }

    @Test
    fun `category filter`() {
        assertEquals(listOf("Diamond"), names(MarketQuery(category = Category.ORES)))
        assertEquals(listOf("bread crumbs", "Bread"), names(MarketQuery(category = Category.FOOD)))
        assertTrue(names(MarketQuery(category = Category.OTHER)).isEmpty())
    }

    @Test
    fun `search is case insensitive over name, key and seller and needs all words`() {
        assertEquals(listOf("bread crumbs", "Bread"), names(MarketQuery(search = "BREAD")))
        assertEquals(listOf("Iron Pickaxe"), names(MarketQuery(search = "hans")))
        assertEquals(listOf("bread crumbs"), names(MarketQuery(search = "custom thing")))
        assertEquals(listOf("Iron Pickaxe"), names(MarketQuery(search = "  iron   pick ")))
        assertTrue(names(MarketQuery(search = "iron diamond")).isEmpty())
        assertEquals(listOf("Diamond"), names(MarketQuery(category = Category.ORES, search = "dia")))
    }

    @Test
    fun `sort by price ascending, ties newest first`() {
        assertEquals(listOf("Oak Log", "bread crumbs", "Bread", "Iron Pickaxe", "Diamond"), names(MarketQuery(sort = SortOrder.PRICE_ASC)))
    }

    @Test
    fun `sort by price descending`() {
        assertEquals(listOf("Diamond", "Iron Pickaxe", "bread crumbs", "Bread", "Oak Log"), names(MarketQuery(sort = SortOrder.PRICE_DESC)))
    }

    @Test
    fun `sort by name case insensitive`() {
        assertEquals(listOf("Bread", "bread crumbs", "Diamond", "Iron Pickaxe", "Oak Log"), names(MarketQuery(sort = SortOrder.NAME)))
    }

    @Test
    fun `category suggestion`() {
        assertEquals(Category.FOOD, Category.suggest("bread", isBlock = false, isEdible = true))
        assertEquals(Category.ORES, Category.suggest("deepslate_iron_ore", isBlock = true, isEdible = false))
        assertEquals(Category.ORES, Category.suggest("diamond", isBlock = false, isEdible = false))
        assertEquals(Category.ORES, Category.suggest("raw_gold_block", isBlock = true, isEdible = false))
        assertEquals(Category.TOOLS, Category.suggest("diamond_pickaxe", isBlock = false, isEdible = false))
        assertEquals(Category.TOOLS, Category.suggest("shears", isBlock = false, isEdible = false))
        assertEquals(Category.BLOCKS, Category.suggest("oak_log", isBlock = true, isEdible = false))
        assertEquals(Category.OTHER, Category.suggest("ender_pearl", isBlock = false, isEdible = false))
    }
}
