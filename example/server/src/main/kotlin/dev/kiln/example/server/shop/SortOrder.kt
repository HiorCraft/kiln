package dev.kiln.example.server.shop

enum class SortOrder(val label: String) {
    PRICE_ASC("Preis aufst."),
    PRICE_DESC("Preis abst."),
    NEWEST("Neueste"),
    NAME("Name"),
}
