package dev.kiln.server.api.creative

import dev.kiln.server.api.Registration
import net.kyori.adventure.key.Key
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * Creative-inventory content shown by the Kiln runtime: own tabs (at most 8) and items added to vanilla tabs. The
 * client only displays them; taking an item out is vanilla creative behaviour (the client sends the full stack), so
 * custom items stay custom items. Items are produced on every update, so they follow later definition changes.
 * Removed when the plugin is disabled.
 */
public interface CreativeTabs {
    /**
     * Registers own tab [key] (namespace = an extension of [plugin]).
     * @throws IllegalArgumentException wrong namespace; @throws IllegalStateException duplicate or more than 8 tabs.
     */
    public fun register(plugin: Plugin, key: Key, init: CreativeTabBuilder.() -> Unit): Registration

    /** Appends [items] to vanilla tab [tab] (e.g. [INGREDIENTS]). */
    public fun addToVanillaTab(plugin: Plugin, tab: Key, items: () -> List<ItemStack>): Registration

    /** Sends the creative content again (call after changing what the item suppliers return). */
    public fun refresh()

    public companion object {
        public val BUILDING_BLOCKS: Key = Key.key("minecraft", "building_blocks")
        public val COLORED_BLOCKS: Key = Key.key("minecraft", "colored_blocks")
        public val NATURAL_BLOCKS: Key = Key.key("minecraft", "natural_blocks")
        public val FUNCTIONAL_BLOCKS: Key = Key.key("minecraft", "functional_blocks")
        public val REDSTONE_BLOCKS: Key = Key.key("minecraft", "redstone_blocks")
        public val TOOLS_AND_UTILITIES: Key = Key.key("minecraft", "tools_and_utilities")
        public val COMBAT: Key = Key.key("minecraft", "combat")
        public val FOOD_AND_DRINKS: Key = Key.key("minecraft", "food_and_drinks")
        public val INGREDIENTS: Key = Key.key("minecraft", "ingredients")
        public val SPAWN_EGGS: Key = Key.key("minecraft", "spawn_eggs")
    }
}
