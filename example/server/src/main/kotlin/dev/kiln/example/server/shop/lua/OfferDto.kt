package dev.kiln.example.server.shop.lua

/** One market offer for the client. [item] = `ExtensionItems.encode` of the single-item template. */
data class OfferDto(
    val id: Long,
    val item: String,
    val name: String,
    val key: String,
    val cat: String,
    val price: Long,
    val amount: Int,
    val seller: String,
    val own: Boolean,
    /** Creation time (epoch millis) for "newest first". */
    val created: Long,
    /** Milliseconds until the offer expires; `null` = never. */
    val expiresIn: Long?,
)
