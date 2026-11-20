package dev.kiln.example.server.demos

import com.google.gson.JsonObject
import dev.kiln.server.api.extension.Extension
import dev.kiln.server.api.extension.ExtensionChannel
import dev.kiln.server.api.extension.channel
import dev.kiln.server.api.registerExtension
import dev.kiln.example.server.ExampleCommands
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.logging.Level

/*
 * Shared plumbing of the five ui_kit demo GUIs (fabrik, questlog, warps, bestenliste, profil). Every demo is a Lua
 * extension with the same channel scheme as the Lua shop (`docs/ui_kit.md`):
 *
 * C->S  open    {}            player pressed the keybind -> server answers with `open`
 * S->C  open    <state>       full state, the client opens its screen
 * S->C  state   <state>       live update, only while the screen is open
 * C->S  closed  {}            screen closed -> no more live updates
 * C->S  <action> {req, ...}   requests, validated field by field (RequestFields), rate limited (RequestGuard)
 * S->C  result  DemoResult    answer to a request with the same `req`
 */

/**
 * Bukkit side of a demo: registers the extension [id] from this jar, tracks which players have the screen open,
 * sends state/results and runs the command (`/<command> [open]`). Subclasses provide the [state] and register their
 * requests with [request]; all validation lives in their (Bukkit-free, tested) controllers.
 */
abstract class LuaDemo(protected val plugin: JavaPlugin, id: String) : Listener, TabExecutor {
    val extension: Extension = plugin.registerExtension(id)

    /** Players with the screen open (they get live updates). */
    protected val openPlayers = LinkedHashSet<UUID>()

    private val openChannel: ExtensionChannel<Any> = extension.channel<Any>("open") { player, _ -> open(player) }
    private val stateChannel: ExtensionChannel<Any> = extension.channel("state")
    private val resultChannel: ExtensionChannel<DemoResult> = extension.channel("result")

    init {
        extension.channel<JsonObject>("closed") { player, _ -> openPlayers.remove(player.uniqueId) }
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /** Full state for [player] (a data class, sent as JSON). */
    protected abstract fun state(player: Player): Any

    /** Called when a player leaves (forget per-player data). */
    protected open fun forget(player: UUID) {}

    fun open(player: Player) {
        openPlayers += player.uniqueId
        guarded(player) { openChannel.send(player, state(player)) }
    }

    /** Sends the state to [player] if the screen is open. */
    fun push(player: Player) {
        if (player.uniqueId in openPlayers) guarded(player) { stateChannel.send(player, state(player)) }
    }

    /** Sends the state to every player with the screen open. */
    fun pushAll() {
        for (uuid in openPlayers.toList()) Bukkit.getPlayer(uuid)?.let(::push)
    }

    /**
     * Registers the request channel [name]: [handler] validates and executes it (usually a controller call with its
     * own [RequestGuard]); the result goes back on `result`, then [afterRequest] runs (default: push the new state).
     */
    protected fun request(name: String, handler: (player: Player, msg: JsonObject) -> DemoResult) {
        extension.channel<JsonObject>(name) { player, msg ->
            val result = handler(player, msg)
            resultChannel.send(player, result)
            afterRequest(player, result)
        }
    }

    protected open fun afterRequest(player: Player, result: DemoResult) {
        if (result.ok) push(player)
    }

    private inline fun guarded(player: Player, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            plugin.logger.log(Level.WARNING, "${extension.id}: state for ${player.name} could not be sent", e)
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = ExampleCommands.requirePlayer(sender) ?: return true
        command(player, args.firstOrNull()?.lowercase())
        return true
    }

    /** `/<command> [arg]`; default: open the screen. */
    protected open fun command(player: Player, arg: String?) {
        open(player)
    }

    protected open fun completions(): List<String> = emptyList()

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> =
        ExampleCommands.completeFirstArgument(args, completions())

    @EventHandler
    fun onDemoQuit(event: PlayerQuitEvent) {
        openPlayers.remove(event.player.uniqueId)
        forget(event.player.uniqueId)
    }
}
