package dev.kiln.example.server.shop.lua

/** An item in one of the player's storage slots (0..35), as seen by the server. */
data class SlotContent<I>(
    /** Single-item copy of the stack. */
    val template: I,
    val amount: Int,
    /** Namespaced item id, e.g. `minecraft:oak_log` (what the client sends as `item`). */
    val key: String,
    /** Key path, e.g. `oak_log` (searchable, stored in the offer). */
    val keyPath: String,
    /** Plain, searchable name. */
    val name: String,
)
