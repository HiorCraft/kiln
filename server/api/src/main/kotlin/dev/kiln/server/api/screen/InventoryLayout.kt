package dev.kiln.server.api.screen

/** Slot layout of the player inventory as drawn by [ContainerBuilder.inventoryGrid] (player inventory numbering). */
internal object InventoryLayout {
    /** Slots per row (also the size of the hotbar, slots `0..8`). */
    const val COLUMNS = 9

    /** Rows of the main inventory, drawn above the hotbar. */
    const val MAIN_ROWS = 3

    /** Slot number of the main inventory's top-left slot; the main inventory is `9..35`. */
    const val FIRST_MAIN_SLOT = 9
}
