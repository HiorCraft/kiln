package dev.kiln.example.server.shop.lua

/**
 * Answer to a request. [error] is shown to the player; [fields] maps form fields (`amount`, `price`, `item`,
 * `category`) to messages; [newPrice] is set when a purchase failed because the price changed.
 */
data class ShopResult(
    val req: Long,
    val action: String,
    val ok: Boolean,
    val message: String? = null,
    val error: String? = null,
    val fields: Map<String, String>? = null,
    val newPrice: Long? = null,
)
