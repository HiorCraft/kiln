package dev.kiln.client.ext

import dev.kiln.client.lua.items.ItemRef
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack

/** A decoded item for Lua ([ItemRef]): an immutable display copy of a vanilla stack (count 1). */
class StackItemRef(val stack: ItemStack) : ItemRef {
    override val id: String = BuiltInRegistries.ITEM.getKey(stack.item).toString()
    override val name: String = stack.hoverName.string
    override val maxStackSize: Int = stack.maxStackSize
}
