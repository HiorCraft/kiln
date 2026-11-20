package dev.kiln.example.server.shop

enum class ShopTab(val label: String, val hint: String) {
    MARKET("Markt", "Angebote aller Spieler durchsuchen und kaufen"),
    SELL("Verkaufen", "Items aus deinem Inventar anbieten"),
    MY_OFFERS("Meine Angebote", "Eigene Angebote verwalten"),
}
