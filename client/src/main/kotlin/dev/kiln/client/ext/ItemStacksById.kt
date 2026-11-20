package dev.kiln.client.ext

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/** Item stacks by id string (immutable display copies), for Lua drawing. */
internal object ItemStacksById {
    /** The cache is dropped when it grows past this many ids. */
    private const val MAX_ENTRIES = 512

    private val stacks = HashMap<String, ItemStack?>()

    fun get(id: String): ItemStack? = stacks.getOrPut(id) {
        if (stacks.size > MAX_ENTRIES) stacks.clear()
        Identifier.tryParse(id)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.let { ItemStack(it) }
    }
}
