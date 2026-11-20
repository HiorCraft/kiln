package dev.kiln.example.server.shop.lua

import java.util.UUID

/** What the controller needs from the game: the player's storage inventory (main thread only). */
interface ShopInventoryAccess<I> {
    /** How many more of [item] fit into [player]'s storage slots. */
    fun freeSpace(player: UUID, item: I): Long

    /** Gives [amount] of [item]; returns how many had to be dropped. */
    fun give(player: UUID, item: I, amount: Int): Int

    /** Content of storage slot [slot] (0..35) or `null` if empty / player offline. */
    fun slot(player: UUID, slot: Int): SlotContent<I>?

    /** How many items similar to [template] (same item and data) the player has. */
    fun countSimilar(player: UUID, template: I): Int

    /** Removes [amount] items similar to [template], starting at [preferredSlot]; `false` (nothing changed) if too few. */
    fun take(player: UUID, template: I, amount: Int, preferredSlot: Int): Boolean
}
