package dev.kiln.example.server.shop

import dev.kiln.server.api.screen.ScreenHandle
import org.bukkit.inventory.ItemStack
import java.util.UUID

enum class ShopTab(val label: String, val hint: String) {
    MARKET("Markt", "Angebote aller Spieler durchsuchen und kaufen"),
    SELL("Verkaufen", "Items aus deinem Inventar anbieten"),
    MY_OFFERS("Meine Angebote", "Eigene Angebote verwalten"),
}

/** UI state of one player's shop; the screen content is a pure function of this plus the market. */
class ShopSession(val playerId: UUID) {
    var handle: ScreenHandle? = null

    var tab = ShopTab.MARKET

    // market
    var search = ""
    var category = Category.ALL
    var sort = SortOrder.NEWEST

    // buy dialog (open while buyOfferId != null), confirmation on top while buyConfirm
    var buyOfferId: Long? = null
    var buyAmount = 1
    var buyAmountInvalid = false
    var buyShulker = false
    var buyPriceSeen = 0L
    var buyConfirm = false
    var buyError: String? = null

    // sell form
    var sellSlot: Int? = null
    var sellTemplate: ItemStack? = null
    var sellAmount = ""
    var sellPrice = ""
    var sellDuration = ListingDuration.H24
    var sellCategory: Category? = null
    var sellNotify = true
    var sellSubmitted = false
    var sellConfirm = false
    var sellError: String? = null

    // my offers
    var withdrawOfferId: Long? = null
    var priceEditOfferId: Long? = null
    var priceEditText = ""
    var priceEditError: String? = null

    /** Cheap fingerprint of the inventory at the last render (to refresh the sell tab when it changes). */
    var inventorySignature = 0

    fun closeBuyDialog() {
        buyOfferId = null
        buyConfirm = false
        buyError = null
        buyAmountInvalid = false
        buyShulker = false
    }

    fun openBuyDialog(offerId: Long, price: Long) {
        closeBuyDialog()
        buyOfferId = offerId
        buyAmount = 1
        buyPriceSeen = price
    }

    fun clearSellForm() {
        sellSlot = null
        sellTemplate = null
        sellAmount = ""
        sellSubmitted = false
        sellConfirm = false
        sellError = null
        sellCategory = null
    }

    fun closeMyOfferDialogs() {
        withdrawOfferId = null
        priceEditOfferId = null
        priceEditError = null
    }
}
