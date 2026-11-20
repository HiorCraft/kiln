package dev.kiln.example.server.shop.lua

import com.google.gson.JsonObject
import dev.kiln.server.api.extension.Extension
import dev.kiln.server.api.extension.ExtensionChannel
import dev.kiln.server.api.extension.ExtensionItems
import dev.kiln.server.api.extension.channel
import dev.kiln.server.api.registerExtension
import dev.kiln.example.server.ExampleCommands
import dev.kiln.example.server.shop.Category
import dev.kiln.example.server.shop.ShopFormat
import dev.kiln.example.server.shop.ShopService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.logging.Level

/**
 * `/shop`: the player market as Lua extension `shop` (screen drawn on the client with `ui_kit`). Shares the market
 * with the server-rendered `/shopdsl` ([dsl]); every change in either view reaches both. All logic and validation is
 * in [LuaShopController]; this class wires channels, commands and the inventory of online players.
 */
class LuaShopService(private val plugin: JavaPlugin, private val dsl: ShopService) : TabExecutor, Listener {
    // needs ui_kit, which ExamplePlugin registers for every demo that uses it
    val extension: Extension = plugin.registerExtension("shop")

    private val access = BukkitInventoryAccess(dsl)
    private val controller = LuaShopController(dsl.market, access)

    /** Players with the Lua shop open (they get live updates). */
    private val openPlayers = HashSet<UUID>()
    private val inventorySignatures = HashMap<UUID, Int>()
    private var ticks = 0

    private val openChannel: ExtensionChannel<ShopState>
    private val stateChannel: ExtensionChannel<ShopState> = extension.channel("state")
    private val resultChannel: ExtensionChannel<ShopResult> = extension.channel("result")

    init {
        openChannel = extension.channel("open", ShopState::class.java) { player, _ -> open(player, null) }
        // requests arrive as raw JSON objects and are validated field by field (LuaShopController)
        extension.channel<JsonObject>("closed") { player, _ -> openPlayers.remove(player.uniqueId) }
        extension.channel<JsonObject>("buy") { player, msg ->
            handle(player, controller.buy(player.uniqueId, player.name, msg, System.currentTimeMillis()))
        }
        extension.channel<JsonObject>("sell") { player, msg ->
            handle(player, controller.sell(player.uniqueId, player.name, msg, System.currentTimeMillis()))
        }
        extension.channel<JsonObject>("withdraw") { player, msg ->
            handle(player, controller.withdraw(player.uniqueId, msg, System.currentTimeMillis()))
        }
        extension.channel<JsonObject>("reprice") { player, msg ->
            handle(player, controller.reprice(player.uniqueId, msg, System.currentTimeMillis()))
        }
        // changes made in the DSL shop (or by us, via dsl.refreshAll) reach every open Lua shop
        dsl.onChange = { broadcastState() }
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, TICK_PERIOD, TICK_PERIOD)
    }

    // ---- state ----------------------------------------------------------------------------------------------

    private fun state(player: Player, open: Boolean, tab: String?): ShopState =
        controller.state(player.uniqueId, System.currentTimeMillis(), ExtensionItems::encode, open, tab, suggestions(player))

    /** Suggested category for every item type in the player's storage slots. */
    private fun suggestions(player: Player): Map<String, Category> {
        val result = HashMap<String, Category>()
        for (index in 0 until LuaShopController.STORAGE_SLOTS) {
            val stack = player.inventory.getItem(index)?.takeUnless { it.isEmpty } ?: continue
            result.getOrPut(stack.type.key.asString()) { dsl.suggestCategory(stack) }
        }
        return result
    }

    fun open(player: Player, tab: String?) {
        openPlayers += player.uniqueId
        inventorySignatures[player.uniqueId] = dsl.inventorySignature(player)
        send(player) { openChannel.send(player, state(player, open = true, tab = tab)) }
    }

    private fun sendState(player: Player) {
        send(player) { stateChannel.send(player, state(player, open = false, tab = null)) }
    }

    private fun broadcastState() {
        for (uuid in openPlayers.toList()) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            sendState(player)
        }
    }

    private inline fun send(player: Player, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            plugin.logger.log(Level.WARNING, "Shop state for ${player.name} could not be sent", e)
        }
    }

    private fun tick() {
        ticks++
        for (uuid in openPlayers.toList()) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            val signature = dsl.inventorySignature(player)
            // inventory changed (category suggestions) or every 30 s (expired offers, remaining times)
            if (signature != inventorySignatures[uuid] || ticks % TICKS_PER_TIME_REFRESH == 0) {
                inventorySignatures[uuid] = signature
                sendState(player)
            }
        }
    }

    // ---- requests -------------------------------------------------------------------------------------------

    private fun handle(player: Player, outcome: ShopOutcome<ItemStack>) {
        resultChannel.send(player, outcome.result)
        if (outcome.dropped > 0) {
            player.sendMessage(Component.text("Inventar voll: ${outcome.dropped} Stück wurden vor dir gedroppt.", NamedTextColor.YELLOW))
        }
        outcome.sale?.let { dsl.notifySeller(it.offer, it.amount, it.total, it.buyerName) }
        // re-renders the DSL shops and (via dsl.onChange) sends the new state to every open Lua shop
        if (outcome.changed) dsl.refreshAll()
    }

    // ---- command / events -----------------------------------------------------------------------------------

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = ExampleCommands.requirePlayer(sender) ?: return true
        when (args.firstOrNull()?.lowercase()) {
            null, "open", "markt" -> open(player, "market")
            "verkaufen", "sell" -> open(player, "sell")
            "angebote", "mine" -> open(player, "mine")
            "balance" -> player.sendMessage(Component.text("Kontostand: ${ShopFormat.money(dsl.market.balance(player.uniqueId))}", NamedTextColor.GOLD))
            else -> player.sendMessage(Component.text("Benutzung: /shop [markt|verkaufen|angebote|balance]", NamedTextColor.RED))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> =
        ExampleCommands.completeFirstArgument(args, listOf("markt", "verkaufen", "angebote", "balance"))

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        openPlayers.remove(uuid)
        inventorySignatures.remove(uuid)
        controller.forget(uuid)
    }

    private companion object {
        /** Server ticks between two [tick]s (1 s). */
        const val TICK_PERIOD = 20L

        /** Every this many [tick]s (30 s) open shops get a new state for expired offers and remaining times. */
        const val TICKS_PER_TIME_REFRESH = 30
    }
}
