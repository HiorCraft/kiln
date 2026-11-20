package dev.kiln.server.runtime.item

import dev.kiln.server.api.item.CustomItem
import dev.kiln.server.api.item.CustomItemBuilder
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import net.kyori.adventure.key.Key
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

class CustomItemImpl(
    private val registry: CustomItemRegistry,
    override val plugin: Plugin,
    override val key: Key,
    val definition: CustomItemBuilder,
) : CustomItem {
    private val model: Key = CustomItemCatalog.modelOf(key, definition.model)

    override fun create(amount: Int): ItemStack {
        val stack = definition.base.createItemStack(1)
        stack.setData(DataComponentTypes.ITEM_MODEL, model)
        definition.name?.let { stack.setData(DataComponentTypes.ITEM_NAME, it) }
        if (definition.lore.isNotEmpty()) stack.setData(DataComponentTypes.LORE, ItemLore.lore(definition.lore))
        definition.components.applyTo(stack)
        stack.editPersistentDataContainer { it.set(CustomItemRegistry.ITEM_KEY, PersistentDataType.STRING, key.asString()) }
        require(amount in 1..stack.maxStackSize) { "amount $amount outside 1..${stack.maxStackSize}" }
        stack.amount = amount
        return stack
    }

    override fun matches(stack: ItemStack?): Boolean = registry.of(stack) === this

    override fun unregister() = registry.remove(this)

    override fun toString(): String = "CustomItem(${key.asString()})"
}
