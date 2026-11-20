package dev.kiln.example.server.shop.lua

/**
 * Result of a request: the [result] for the requesting client; [changed] = market or balances changed (broadcast new
 * state); [sale] = notify the seller; [dropped] = items that did not fit into the inventory (chat feedback).
 */
data class ShopOutcome<I>(
    val result: ShopResult,
    val changed: Boolean = false,
    val sale: Sale<I>? = null,
    val dropped: Int = 0,
)
