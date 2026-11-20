package dev.kiln.server.runtime.enchantment

import dev.kiln.server.api.Registration
import dev.kiln.server.api.enchantment.CustomEnchantments
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.logging.Level
import java.util.logging.Logger

/** [CustomEnchantments]: handlers per enchantment key, dispatched from Bukkit events and a 20-tick task. */
class EnchantmentHookRegistry(private val logger: Logger) : CustomEnchantments, Listener {
    private class Handler<F>(val plugin: Plugin, val key: Key, val call: F)

    private val hits = ArrayList<Handler<(LivingEntity, Entity, Int, EntityDamageByEntityEvent) -> Unit>>()
    private val breaks = ArrayList<Handler<(Player, Block, Int, BlockBreakEvent) -> Unit>>()
    private val ticks = ArrayList<Handler<(Player, Int) -> Unit>>()

    private fun enchantment(key: Key): Enchantment? =
        RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key)

    override fun level(stack: ItemStack?, key: Key): Int {
        if (stack == null || stack.isEmpty) return 0
        val enchantment = enchantment(key) ?: return 0
        return stack.getEnchantmentLevel(enchantment)
    }

    private fun <F> add(list: MutableList<Handler<F>>, handler: Handler<F>): Registration {
        list += handler
        return Registration { list.remove(handler) }
    }

    override fun onHit(plugin: Plugin, key: Key, handler: (LivingEntity, Entity, Int, EntityDamageByEntityEvent) -> Unit): Registration =
        add(hits, Handler(plugin, key, handler))

    override fun onBreak(plugin: Plugin, key: Key, handler: (Player, Block, Int, BlockBreakEvent) -> Unit): Registration =
        add(breaks, Handler(plugin, key, handler))

    override fun onTick(plugin: Plugin, key: Key, handler: (Player, Int) -> Unit): Registration =
        add(ticks, Handler(plugin, key, handler))

    fun removeAll(plugin: Plugin) {
        hits.removeIf { it.plugin === plugin }
        breaks.removeIf { it.plugin === plugin }
        ticks.removeIf { it.plugin === plugin }
    }

    private fun report(handler: Handler<*>, what: String): (Throwable) -> Unit = { e ->
        logger.log(Level.SEVERE, "Enchantment ${handler.key.asString()} ($what) of ${handler.plugin.name} failed", e)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (hits.isEmpty()) return
        val attacker = event.damager as? LivingEntity ?: return
        val weapon = attacker.equipment?.itemInMainHand ?: return
        for (handler in hits.toList()) {
            val level = level(weapon, handler.key)
            if (level <= 0) continue
            EnchantmentHooks.dispatch(listOf { handler.call(attacker, event.entity, level, event) }, report(handler, "onHit"))
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (breaks.isEmpty()) return
        val tool = event.player.inventory.itemInMainHand
        for (handler in breaks.toList()) {
            val level = level(tool, handler.key)
            if (level <= 0) continue
            EnchantmentHooks.dispatch(listOf { handler.call(event.player, event.block, level, event) }, report(handler, "onBreak"))
        }
    }

    /** Called every 20 ticks. */
    fun tick() {
        if (ticks.isEmpty()) return
        for (player in Bukkit.getOnlinePlayers()) {
            val equipment = player.equipment
            val worn = EquipmentSlot.entries.filter { player.canUseEquipmentSlot(it) }.map { equipment.getItem(it) }
            for (handler in ticks.toList()) {
                val level = EnchantmentHooks.highestLevel(worn.map { level(it, handler.key) })
                if (level <= 0) continue
                EnchantmentHooks.dispatch(listOf { handler.call(player, level) }, report(handler, "onTick"))
            }
        }
    }
}
