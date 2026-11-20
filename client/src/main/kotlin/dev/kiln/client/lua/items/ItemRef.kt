package dev.kiln.client.lua.items

/** A decoded item stack held by Lua as an opaque handle (`kiln.items.decode`, `player().inventory()`). */
interface ItemRef {
    /** Item type, e.g. `minecraft:diamond_sword`. */
    val id: String

    /** Display name (custom name or item name) as plain text. */
    val name: String

    val maxStackSize: Int
}
