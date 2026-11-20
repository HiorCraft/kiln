package dev.kiln.server.api.item

import io.papermc.paper.datacomponent.DataComponentType
import org.bukkit.inventory.ItemStack

/** Data components applied to every created stack, in order. */
public class ItemComponents {
    private val actions = ArrayList<(ItemStack) -> Unit>()

    public fun <T : Any> set(type: DataComponentType.Valued<T>, value: T) {
        actions += { it.setData(type, value) }
    }

    public fun set(type: DataComponentType.NonValued) {
        actions += { it.setData(type) }
    }

    /** Removes a component the base item has by default. */
    public fun unset(type: DataComponentType) {
        actions += { it.unsetData(type) }
    }

    /** Applies every component to [stack]. */
    public fun applyTo(stack: ItemStack) {
        for (action in actions) action(stack)
    }
}
