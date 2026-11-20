package dev.kiln.server.api.item

import dev.kiln.server.api.Registration
import net.kyori.adventure.key.Key
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** A registered custom item. */
public interface CustomItem : Registration {
    public val key: Key
    public val plugin: Plugin

    /** A new stack of [amount] (1..max stack size) with every component of the definition. */
    public fun create(amount: Int = 1): ItemStack

    /** `true` if [stack] is an instance of this item. */
    public fun matches(stack: ItemStack?): Boolean
}
