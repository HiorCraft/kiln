package dev.kiln.client.lua.items

/** One non-empty slot of the player's inventory (`kiln.player().inventory()`). */
class InventorySlot(val slot: Int, val item: ItemRef, val count: Int)
