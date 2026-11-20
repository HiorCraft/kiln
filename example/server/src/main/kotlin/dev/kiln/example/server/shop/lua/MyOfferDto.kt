package dev.kiln.example.server.shop.lua

/** One of the player's own offers (also expired ones, which are no longer in the market). */
data class MyOfferDto(
    val id: Long,
    val item: String,
    val name: String,
    val key: String,
    val cat: String,
    val price: Long,
    val amount: Int,
    val expiresIn: Long?,
    val expired: Boolean,
)
