package dev.kiln.example.server.shop

import java.util.Locale
import java.util.UUID

// Pure shop model (no Bukkit types): used by the Paper layer with I = ItemStack and by the tests with I = String.

enum class Category(val label: String) {
    ALL("Alle"),
    BLOCKS("Blöcke"),
    TOOLS("Werkzeuge"),
    ORES("Erze"),
    FOOD("Essen"),
    OTHER("Sonstiges"),
    ;

    companion object {
        /** Categories an offer can be listed in (everything but [ALL]). */
        val listable: List<Category> = entries.filter { it != ALL }

        private val TOOL_SUFFIXES = listOf(
            "_pickaxe", "_axe", "_shovel", "_hoe", "_sword", "_spear", "_mace",
        )
        private val TOOL_ITEMS = setOf(
            "shears", "flint_and_steel", "fishing_rod", "bow", "crossbow", "trident", "mace", "brush", "shield",
            "carrot_on_a_stick", "warped_fungus_on_a_stick", "compass", "recovery_compass", "clock", "spyglass",
            "lead", "bucket",
        )
        private val ORE_ITEMS = setOf(
            "coal", "charcoal", "raw_iron", "raw_gold", "raw_copper", "iron_ingot", "gold_ingot", "copper_ingot",
            "netherite_ingot", "netherite_scrap", "diamond", "emerald", "lapis_lazuli", "redstone", "quartz",
            "amethyst_shard", "ancient_debris", "iron_nugget", "gold_nugget",
        )

        /** Suggested category for an item, from its key path (`diamond_pickaxe`) and material properties. */
        fun suggest(keyPath: String, isBlock: Boolean, isEdible: Boolean): Category = when {
            isEdible -> FOOD
            keyPath.endsWith("_ore") || keyPath in ORE_ITEMS || keyPath.startsWith("raw_") && keyPath.endsWith("_block") -> ORES
            keyPath in TOOL_ITEMS || TOOL_SUFFIXES.any { keyPath.endsWith(it) } -> TOOLS
            isBlock -> BLOCKS
            else -> OTHER
        }
    }
}

enum class SortOrder(val label: String) {
    PRICE_ASC("Preis aufst."),
    PRICE_DESC("Preis abst."),
    NEWEST("Neueste"),
    NAME("Name"),
}

enum class ListingDuration(val label: String, val millis: Long) {
    H1("1 Stunde", 3_600_000L),
    H12("12 Stunden", 12 * 3_600_000L),
    H24("24 Stunden", 24 * 3_600_000L),
    D7("7 Tage", 7 * 24 * 3_600_000L),
}

/**
 * One offer. [item] is a single-item template (amount 1), [amount] how many are left. [sellerId] `null` = server
 * offer. [name] is the plain, searchable name (custom name or English item name).
 */
data class Offer<I>(
    val id: Long,
    val item: I,
    val name: String,
    val itemKey: String,
    val category: Category,
    val pricePerUnit: Long,
    val amount: Int,
    val sellerId: UUID?,
    val sellerName: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val notifyOnSale: Boolean = false,
) {
    fun isExpired(now: Long): Boolean = expiresAt != null && now >= expiresAt
    fun isActive(now: Long): Boolean = amount > 0 && !isExpired(now)
}

data class MarketQuery(
    val category: Category = Category.ALL,
    val search: String = "",
    val sort: SortOrder = SortOrder.NEWEST,
)

object OfferFilter {
    /** Active offers matching [query], sorted. Search is case-insensitive over name, item key and seller. */
    fun <I> apply(offers: Collection<Offer<I>>, query: MarketQuery, now: Long): List<Offer<I>> {
        val terms = query.search.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return offers.asSequence()
            .filter { it.isActive(now) }
            .filter { query.category == Category.ALL || it.category == query.category }
            .filter { offer ->
                if (terms.isEmpty()) return@filter true
                val haystack = "${offer.name} ${offer.itemKey.replace('_', ' ')} ${offer.sellerName}".lowercase(Locale.ROOT)
                terms.all { it in haystack }
            }
            .sortedWith(comparator(query.sort))
            .toList()
    }

    fun <I> comparator(sort: SortOrder): Comparator<Offer<I>> {
        val newest = compareByDescending<Offer<I>> { it.createdAt }.thenByDescending { it.id }
        return when (sort) {
            SortOrder.PRICE_ASC -> compareBy<Offer<I>> { it.pricePerUnit }.then(newest)
            SortOrder.PRICE_DESC -> compareByDescending<Offer<I>> { it.pricePerUnit }.then(newest)
            SortOrder.NEWEST -> newest
            SortOrder.NAME -> compareBy<Offer<I>, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.pricePerUnit }.then(newest)
        }
    }
}

object ShopFormat {
    /** `1234567` -> `1.234.567 Taler`. */
    fun money(amount: Long): String = "${number(amount)} Taler"

    fun number(amount: Long): String = String.format(Locale.GERMANY, "%,d", amount)

    /** Remaining time like `6 Std. 12 Min.`, `3 T. 4 Std.`, `abgelaufen`, `unbegrenzt`. */
    fun remaining(expiresAt: Long?, now: Long): String {
        if (expiresAt == null) return "unbegrenzt"
        val left = expiresAt - now
        if (left <= 0) return "abgelaufen"
        val minutes = (left + 59_999) / 60_000
        val days = minutes / (24 * 60)
        val hours = minutes / 60 % 24
        val mins = minutes % 60
        return when {
            days > 0 -> "$days T. $hours Std."
            hours > 0 -> "$hours Std. $mins Min."
            else -> "$mins Min."
        }
    }

    /** Approximate width of [text] in the vanilla font (GUI pixels, including the 1px spacing). */
    fun width(text: String): Int = text.sumOf { charWidth(it) }

    /** Cuts [text] to fit into [maxWidth] GUI pixels, appending `...` when shortened. */
    fun ellipsize(text: String, maxWidth: Int): String {
        if (width(text) <= maxWidth) return text
        val dots = "..."
        val budget = maxWidth - width(dots)
        if (budget <= 0) return ""
        val sb = StringBuilder()
        var used = 0
        for (c in text) {
            val w = charWidth(c)
            if (used + w > budget) break
            sb.append(c)
            used += w
        }
        return sb.toString().trimEnd() + dots
    }

    private fun charWidth(c: Char): Int = when (c) {
        'i', '!', '.', ',', ':', ';', '|', '\'' -> 2
        'l', '`' -> 3
        't', 'I', '[', ']', ' ', '"', '(', ')', '*', '{', '}' -> 4
        'f', 'k', '<', '>' -> 5
        '@', '~' -> 7
        else -> 6
    }
}

/** Grid math for the offer tiles (pure). */
object ShopLayout {
    /** Number of tile columns for an area of [width] (at least 1). */
    fun columns(width: Int, minTileWidth: Int, gap: Int): Int = maxOf(1, (width + gap) / (minTileWidth + gap))

    /** Width of each of [columns] tiles filling [width]. */
    fun tileWidth(width: Int, columns: Int, gap: Int): Int = maxOf(1, (width - gap * (columns - 1)) / columns)
}
