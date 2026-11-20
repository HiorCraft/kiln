package dev.kiln.server.api.enchantment

import dev.kiln.server.api.Registration
import net.kyori.adventure.key.Key
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * Logic for enchantments (custom ones from [KilnBootstrap] or vanilla ones). Handlers run on the main thread;
 * their exceptions are logged. Removed when [Plugin] is disabled.
 */
public interface CustomEnchantments {
    /** [attacker] hits [victim] with the enchantment on its main-hand item at [level] > 0. */
    public fun onHit(
        plugin: Plugin,
        key: Key,
        handler: (attacker: LivingEntity, victim: Entity, level: Int, event: EntityDamageByEntityEvent) -> Unit,
    ): Registration

    /** [player] breaks [block] with the enchantment on the main-hand item. */
    public fun onBreak(plugin: Plugin, key: Key, handler: (player: Player, block: Block, level: Int, event: BlockBreakEvent) -> Unit): Registration

    /** Every second for each online player wearing or holding the enchantment (highest level of armour and hands). */
    public fun onTick(plugin: Plugin, key: Key, handler: (player: Player, level: Int) -> Unit): Registration

    /** Level of [key] on [stack] (0 if absent or unknown). */
    public fun level(stack: ItemStack?, key: Key): Int
}
