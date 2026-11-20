package dev.kiln.example.server.shop

import java.util.Locale

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
