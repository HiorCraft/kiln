package dev.kiln.server.api.creative

import dev.kiln.server.api.item.CustomItem
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack

/** Definition of an own creative tab. */
public class CreativeTabBuilder {
    public var title: Component = Component.empty()

    /** Tab icon; default: the first item. */
    public var icon: (() -> ItemStack)? = null

    private val suppliers = ArrayList<() -> List<ItemStack>>()

    public fun add(vararg items: CustomItem) {
        suppliers += { items.map { it.create() } }
    }

    public fun add(stack: () -> ItemStack) {
        suppliers += { listOf(stack()) }
    }

    /** All items, in the order they were added. */
    public fun items(): List<ItemStack> = suppliers.flatMap { it() }
}
