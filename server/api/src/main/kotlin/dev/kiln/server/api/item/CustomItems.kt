package dev.kiln.server.api.item

import net.kyori.adventure.key.Key
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * Custom items: item stacks of a vanilla [CustomItemBuilder.base] type with data components (above all
 * `item_model`, pointing at an item definition in an extension's `assets/<ns>/items/`). They are not registry
 * entries; Kiln recognises them by a persistent data key and dispatches the [CustomItemBuilder] hooks.
 */
public interface CustomItems {
    /**
     * Registers the custom item [key]. Its namespace must be the id of an extension registered by [plugin], and
     * the model ([CustomItemBuilder.model], default [key]) must have `assets/<ns>/items/<path>.json` in it.
     * Removed when [plugin] is disabled.
     *
     * @throws IllegalArgumentException wrong namespace or missing item definition.
     * @throws IllegalStateException [key] is already registered.
     */
    public fun register(plugin: Plugin, key: Key, init: CustomItemBuilder.() -> Unit): CustomItem

    /** The custom item [stack] is an instance of, if any. */
    public fun of(stack: ItemStack?): CustomItem?

    public fun get(key: Key): CustomItem?

    public fun all(): Collection<CustomItem>
}
