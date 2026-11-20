package dev.kiln.example.server.shop

data class MarketQuery(
    val category: Category = Category.ALL,
    val search: String = "",
    val sort: SortOrder = SortOrder.NEWEST,
)
