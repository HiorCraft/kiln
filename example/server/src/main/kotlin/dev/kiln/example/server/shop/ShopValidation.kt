package dev.kiln.example.server.shop

object ShopValidation {
    /** Validates the sell form; [available] is how many of the chosen item the player has. */
    fun listing(hasItem: Boolean, available: Int, amountText: String, priceText: String, category: Category?): ListingValidation {
        val errors = LinkedHashMap<String, String>()
        if (!hasItem) errors["item"] = "Wähle links ein Item aus deinem Inventar."
        else if (available <= 0) errors["item"] = "Das Item ist nicht mehr in deinem Inventar."

        val amount = amountText.trim().toIntOrNull()
        when {
            amountText.isBlank() -> errors["amount"] = "Menge fehlt."
            amount == null || amount < 1 -> errors["amount"] = "Menge muss eine ganze Zahl ab 1 sein."
            hasItem && available > 0 && amount > available -> errors["amount"] = "Du hast nur ${ShopFormat.number(available.toLong())} Stück."
        }

        price(priceText)?.let { errors["price"] = it }

        if (category == null || category == Category.ALL) errors["category"] = "Kategorie wählen."
        val price = priceText.trim().toLongOrNull()
        return ListingValidation(errors, amount?.takeIf { "amount" !in errors }, price?.takeIf { "price" !in errors })
    }

    /** Validates a new unit price; `null` = ok. */
    fun price(text: String): String? {
        val price = text.trim().toLongOrNull()
        return when {
            text.isBlank() -> "Preis fehlt."
            price == null || price < 1 || price > Market.MAX_PRICE -> "Preis muss zwischen 1 und ${ShopFormat.number(Market.MAX_PRICE)} liegen."
            else -> null
        }
    }
}
