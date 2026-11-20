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

/** [CustomEnchantments]: handlers per enchantment key, dispatched from Bukkit events and a [TICK_INTERVAL]-tick task. */
class EnchantmentHookRegistry(private val logger: Logger) : CustomEnchantments, Listener {
    private val hits = ArrayList<EnchantmentHandler<(LivingEntity, Entity, Int, EntityDamageByEntityEvent) -> Unit>>()
    private val breaks = ArrayList<EnchantmentHandler<(Player, Block, Int, BlockBreakEvent) -> Unit>>()
    private val ticks = ArrayList<EnchantmentHandler<(Player, Int) -> Unit>>()

    private fun enchantment(key: Key): Enchantment? =
        RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key)

    override fun level(stack: ItemStack?, key: Key): Int {
        if (stack == null || stack.isEmpty) return 0
        val enchantment = enchantment(key) ?: return 0
        return stack.getEnchantmentLevel(enchantment)
    }

    private fun <F> add(list: MutableList<EnchantmentHandler<F>>, handler: EnchantmentHandler<F>): Registration {
        list += handler
        return Registration { list.remove(handler) }
    }

    override fun onHit(plugin: Plugin, key: Key, handler: (LivingEntity, Entity, Int, EntityDamageByEntityEvent) -> Unit): Registration =
        add(hits, EnchantmentHandler(plugin, key, handler))

    override fun onBreak(plugin: Plugin, key: Key, handler: (Player, Block, Int, BlockBreakEvent) -> Unit): Registration =
        add(breaks, EnchantmentHandler(plugin, key, handler))

    override fun onTick(plugin: Plugin, key: Key, handler: (Player, Int) -> Unit): Registration =
        add(ticks, EnchantmentHandler(plugin, key, handler))

    fun removeAll(plugin: Plugin) {
        hits.removeIf { it.plugin === plugin }
        breaks.removeIf { it.plugin === plugin }
        ticks.removeIf { it.plugin === plugin }
    }

    /** Calls [call] for every handler whose enchantment has a level above 0 ([levelOf]); exceptions are logged. */
    private inline fun <F> fire(
        handlers: List<EnchantmentHandler<F>>,
        what: String,
        levelOf: (Key) -> Int,
        crossinline call: (F, Int) -> Unit,
    ) {
        for (handler in handlers.toList()) {
            val level = levelOf(handler.key)
            if (level <= 0) continue
            EnchantmentHooks.dispatch(listOf { call(handler.call, level) }) { e ->
                logger.log(Level.SEVERE, "Enchantment ${handler.key.asString()} ($what) of ${handler.plugin.name} failed", e)
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (hits.isEmpty()) return
        val attacker = event.damager as? LivingEntity ?: return
        val weapon = attacker.equipment?.itemInMainHand ?: return
        fire(hits, "onHit", { level(weapon, it) }) { call, level -> call(attacker, event.entity, level, event) }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (breaks.isEmpty()) return
        val tool = event.player.inventory.itemInMainHand
        fire(breaks, "onBreak", { level(tool, it) }) { call, level -> call(event.player, event.block, level, event) }
    }

    /** Called every [TICK_INTERVAL] ticks. */
    fun tick() {
        if (ticks.isEmpty()) return
        for (player in Bukkit.getOnlinePlayers()) {
            val equipment = player.equipment
            val worn = EquipmentSlot.entries.filter { player.canUseEquipmentSlot(it) }.map { equipment.getItem(it) }
            fire(ticks, "onTick", { key -> EnchantmentHooks.highestLevel(worn.map { level(it, key) }) }) { call, level -> call(player, level) }
        }
    }

    companion object {
        /** Ticks between two [tick] calls. */
        const val TICK_INTERVAL = 20
    }
}
