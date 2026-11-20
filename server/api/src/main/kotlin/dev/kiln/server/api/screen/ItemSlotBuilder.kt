package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack

@KilnDsl
public class ItemSlotBuilder internal constructor() {
    /** Shown item; `null` = empty slot (showing [placeholderSprite] if set). */
    public var item: ScreenItem? = null

    /** GUI sprite for an empty slot, e.g. `Key.key("container/slot/sword")`. */
    public var placeholderSprite: Key? = null
    public var slotBackground: Boolean = true
    public var highlight: Boolean = true
    public var selected: Boolean = false
    public var enabled: Boolean = true
    public var showItemTooltip: Boolean = true

    /** Appended below the item tooltip. */
    public var tooltip: Component? = null
    internal var clickHandler: ((ClickContext) -> Unit)? = null

    /** Sets [item] from a stack (`null`/air = empty); [count] defaults to the stack amount. */
    public fun item(stack: ItemStack?, count: Int? = null) {
        item = ScreenItem.ofNullable(stack, count)
    }

    /** Any mouse button; check [ClickContext.button] and [ClickContext.shift]. */
    public fun onClick(handler: (ClickContext) -> Unit) {
        clickHandler = handler
    }
}
