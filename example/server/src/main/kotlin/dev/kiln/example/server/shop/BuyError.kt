package dev.kiln.example.server.shop

/** Why a purchase is not possible; [message] is shown in the dialog. */
sealed class BuyError(val message: String) {
    data object OfferGone : BuyError("Das Angebot ist nicht mehr verfügbar.")
    data object OwnOffer : BuyError("Das ist dein eigenes Angebot.")
    data class InvalidAmount(val max: Int) : BuyError("Bitte eine Menge von 1 bis ${ShopFormat.number(max.toLong())} wählen.")
    data class NotEnoughStock(val available: Int) : BuyError("Nur noch ${ShopFormat.number(available.toLong())} Stück verfügbar.")
    data class InsufficientFunds(val missing: Long) : BuyError("Nicht genug Geld (es fehlen ${ShopFormat.money(missing)}).")
    data class NoSpace(val space: Long) : BuyError(
        if (space <= 0) "Dein Inventar ist voll." else "Dein Inventar hat nur Platz für ${ShopFormat.number(space)} Stück.",
    )
    data class PriceChanged(val newPrice: Long) : BuyError("Der Preis wurde geändert (jetzt ${ShopFormat.money(newPrice)}/Stk.).")
}
