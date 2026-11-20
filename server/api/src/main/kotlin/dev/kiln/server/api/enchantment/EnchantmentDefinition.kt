package dev.kiln.server.api.enchantment

import io.papermc.paper.registry.tag.TagKey
import net.kyori.adventure.text.Component
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemType

/** One custom enchantment (mirrors Paper's `EnchantmentRegistryEntry.Builder`). */
public class EnchantmentDefinition {
    /** Name shown on items; default `translatable("<ns>.enchantment.<path>")`. */
    public var description: Component? = null

    /** Items it can be applied to (anvil, commands); required. */
    public var supportedItems: TagKey<ItemType>? = null

    /** Items it is offered for in the enchanting table; `null` = [supportedItems]. */
    public var primaryItems: TagKey<ItemType>? = null

    public var maxLevel: Int = 1
    public var weight: Int = 1
    public var anvilCost: Int = 1

    /** Enchanting-table cost at level 1 and per further level (base, per level). */
    public var minCost: Pair<Int, Int> = 1 to 10
    public var maxCost: Pair<Int, Int> = 21 to 10

    internal var slots: List<EquipmentSlotGroup> = listOf(EquipmentSlotGroup.ANY)

    public fun slots(vararg groups: EquipmentSlotGroup) {
        slots = groups.toList()
    }
}
