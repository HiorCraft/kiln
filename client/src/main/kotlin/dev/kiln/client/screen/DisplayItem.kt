package dev.kiln.client.screen

import dev.kiln.protocol.ItemStackData
import net.minecraft.world.item.ItemStack

/** A decoded [ItemStackData]: the stack to draw (count 1) plus the display count (may exceed 99). */
class DisplayItem(val stack: ItemStack, val count: Int, val failed: Boolean) {
    /** Count text for `itemDecorations` (`null` = vanilla behaviour for a single item). */
    val countText: String? = CountFormat.decoration(count)
}
