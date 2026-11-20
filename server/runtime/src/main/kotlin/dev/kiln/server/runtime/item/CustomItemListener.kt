package dev.kiln.server.runtime.item

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEvent

/** Dispatches Bukkit events to the hooks of [CustomItemImpl]s. */
class CustomItemListener(private val registry: CustomItemRegistry) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val stack = event.item ?: return
        val item = registry.of(stack) as? CustomItemImpl ?: return
        val hook = item.definition.onUse ?: return
        registry.hook(item, "onUse") { hook(event.player, stack, event) }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onHit(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        val stack = player.inventory.itemInMainHand
        val item = registry.of(stack) as? CustomItemImpl ?: return
        val hook = item.definition.onHit ?: return
        registry.hook(item, "onHit") { hook(player, event.entity, stack, event) }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val stack = event.player.inventory.itemInMainHand
        val item = registry.of(stack) as? CustomItemImpl ?: return
        val hook = item.definition.onBreakBlock ?: return
        registry.hook(item, "onBreakBlock") { hook(event.player, event.block, stack, event) }
    }
}
