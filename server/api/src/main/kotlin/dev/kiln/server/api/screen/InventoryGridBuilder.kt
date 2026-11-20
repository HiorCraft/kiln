package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import org.bukkit.inventory.ItemStack

@KilnDsl
public class InventoryGridBuilder internal constructor() {
    public var includeHotbar: Boolean = true

    /** Vertical gap between main inventory and hotbar, in GUI pixels. */
    public var hotbarGap: Int = 4
    internal var customizer: (ItemSlotBuilder.(slot: Int, item: ItemStack?) -> Unit)? = null
    internal var clickHandler: ((InventorySlotClickContext) -> Unit)? = null

    /** Customizes every slot (e.g. `enabled = item != null && sellable(item)`, `selected = slot == chosen`). */
    public fun slots(customizer: ItemSlotBuilder.(slot: Int, item: ItemStack?) -> Unit) {
        this.customizer = customizer
    }

    public fun onClick(handler: (InventorySlotClickContext) -> Unit) {
        clickHandler = handler
    }
}
