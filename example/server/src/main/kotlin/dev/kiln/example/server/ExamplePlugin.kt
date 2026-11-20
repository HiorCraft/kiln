package dev.kiln.example.server

import dev.kiln.server.api.Kiln
import dev.kiln.server.api.currentKilnScreen
import dev.kiln.server.api.extension.Extension
import dev.kiln.server.api.extension.ExtensionChannel
import dev.kiln.server.api.extension.channel
import dev.kiln.server.api.keybind.KeyCode
import dev.kiln.server.api.openScreen
import dev.kiln.server.api.registerExtension
import dev.kiln.server.api.screen.CloseReason
import dev.kiln.server.api.screen.Layout
import dev.kiln.server.api.screen.ScreenDefinition
import dev.kiln.server.api.screen.ScreenHandle
import dev.kiln.server.api.screen.TextAlign
import dev.kiln.server.api.screen.screen
import dev.kiln.example.server.demos.LuaDemo
import dev.kiln.example.server.demos.bestenliste.LeaderboardDemo
import dev.kiln.example.server.demos.fabrik.FactoryDemo
import dev.kiln.example.server.demos.profil.ProfileDemo
import dev.kiln.example.server.demos.questlog.QuestDemo
import dev.kiln.example.server.demos.warps.WarpDemo
import dev.kiln.example.server.shop.ShopService
import dev.kiln.example.server.shop.lua.LuaShopService
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale
import java.util.UUID

/** Message on `kiln_example:hello` (both directions). Client input: [text] may be missing. */
data class HelloMessage(val text: String?)

/** Factory state for the Lua HUD overlay on `kiln_example:machine` (server -> client only). */
data class MachineState(val running: Boolean, val energy: Float, val output: Int)

/**
 * Demo of the kiln server API: registers the Lua extension `kiln_example` (gauge widget, hello
 * keybind J, factory HUD) from this jar's resources; `/pbdemo` opens a factory GUI with live updates; two server
 * keybinds (G = open factory, H = ping).
 */
class ExamplePlugin : JavaPlugin(), Listener, TabExecutor {

    /** Per-player factory state; the screen content lambda simply reads it on every refresh. */
    private class Factory {
        var running = false
        var energy = 0f
        var counter = 0
        var machineName = "Maschine 1"
        var input = 32
        var output = 0
    }

    private val factories = HashMap<UUID, Factory>()
    private val openFactories = HashMap<UUID, ScreenHandle>()
    private val pingPressedAt = HashMap<UUID, Long>()

    private lateinit var extension: Extension
    private lateinit var machineChannel: ExtensionChannel<MachineState>

    override fun onEnable() {
        val bridge = Kiln.get()

        // src/main/resources/extensions/kiln_example/ -> delivered to every client while it connects
        extension = registerExtension("kiln_example")
        extension.channel<HelloMessage>("hello") { player, msg ->
            // untrusted client input: bounded text, no control characters
            val text = msg.text?.take(256)?.filterNot { it.isISOControl() } ?: return@channel
            player.sendMessage(Component.text("Lua-Erweiterung sagt: \"$text\"", NamedTextColor.LIGHT_PURPLE))
            send(player, HelloMessage("Hallo zurück, ${player.name}!"))
        }
        machineChannel = extension.channel<MachineState>("machine")

        bridge.registerKeybind(this, Kiln.key(this, "open_factory"), "Factory öffnen", KeyCode.keyboard("g")) {
            onPress { player -> openFactory(player) }
        }
        bridge.registerKeybind(this, Kiln.key(this, "ping"), "Ping", KeyCode.keyboard("h")) {
            onPress { player ->
                pingPressedAt[player.uniqueId] = System.currentTimeMillis()
                player.sendMessage(Component.text("Ping: gedrückt", NamedTextColor.AQUA))
            }
            onRelease { player ->
                val held = pingPressedAt.remove(player.uniqueId)?.let { System.currentTimeMillis() - it }
                player.sendMessage(Component.text("Ping: losgelassen" + (held?.let { " (${it} ms gehalten)" } ?: ""), NamedTextColor.AQUA))
            }
        }

        ExampleCommands.register(this, "pbdemo", this)
        // /shopdsl: server-rendered market (screen DSL); /shop: the same market as Lua extension (ui_kit + shop)
        val dslShop = ShopService(this)
        ExampleCommands.register(this, "shopdsl", dslShop)
        val luaShop = LuaShopService(this, dslShop)
        ExampleCommands.register(this, "shop", luaShop)
        // five ui_kit demo GUIs (Lua extensions with their own keybind): command -> demo
        val demos: Map<String, (JavaPlugin) -> LuaDemo> = linkedMapOf(
            "fabrik" to ::FactoryDemo,
            "quests" to ::QuestDemo,
            "warps" to ::WarpDemo,
            "bestenliste" to ::LeaderboardDemo,
            "profil" to ::ProfileDemo,
        )
        for ((command, create) in demos) ExampleCommands.register(this, command, create(this))
        server.pluginManager.registerEvents(this, this)

        // live updates: the factory runs once per second and every open GUI is re-rendered
        server.scheduler.runTaskTimer(this, Runnable { tick() }, 20L, 20L)
    }

    private fun tick() {
        for ((uuid, factory) in factories) {
            if (!factory.running) continue
            factory.energy += 0.1f
            if (factory.energy >= 1f) {
                factory.energy = 0f
                if (factory.input > 0) {
                    factory.input--
                    factory.output++
                }
            }
            openFactories[uuid]?.refresh()
            sendMachineState(uuid, factory)
        }
    }

    /** Feeds the Lua HUD overlay of the factory owner. */
    private fun sendMachineState(uuid: UUID, factory: Factory) {
        val player = server.getPlayer(uuid) ?: return
        machineChannel.send(player, MachineState(factory.running, factory.energy.coerceIn(0f, 1f), factory.output))
    }

    // ---- GUI ------------------------------------------------------------------------------------------------

    private fun openFactory(player: Player) {
        val factory = factories.getOrPut(player.uniqueId) { Factory() }
        openFactories[player.uniqueId] = player.openScreen(factoryScreen(factory))
    }

    private fun factoryScreen(factory: Factory): ScreenDefinition {
        // the text field keeps its initial value: re-sending the live value would overwrite what the player is typing
        val initialName = factory.machineName
        return screen(WIDTH, HEIGHT) {
            layout = Layout.Column(gap = 3, padding = 6)
            background = Color.fromARGB(0xE0, 0x20, 0x22, 0x28)
            border = Color.fromRGB(0x8899AA)

            panel(width = INNER, height = 32, id = "header") {
                layout = Layout.Row(gap = 6, padding = 0)
                // texture of the Lua extension package: <extension id>:<path inside the package>
                image(Key.key(extension.id, "textures/factory_icon.png"), width = 32, height = 32, id = "icon")
                text(
                    Component.text("Factory", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                    y = 12, width = INNER - 38, id = "title",
                )
            }

            text("Energie: ${(factory.energy * 100).toInt()} %", width = INNER, id = "energy_label")
            progressBar(factory.energy, width = INNER, height = 6, color = Color.fromRGB(0xFFCC33), id = "energy")

            panel(width = INNER, height = 40, id = "power_row") {
                layout = Layout.Row(gap = 6, padding = 0)
                button("power", if (factory.running) "Ausschalten" else "Einschalten", y = 10, width = 90, height = 20) {
                    tooltip = Component.text("Startet bzw. stoppt die Produktion")
                    onClick { ctx ->
                        factory.running = !factory.running
                        ctx.screen.refresh()
                        sendMachineState(ctx.player.uniqueId, factory)
                    }
                }
                // rendered by the Lua widget kiln_example:gauge (main.lua)
                custom("gauge", extension.widget("gauge"), width = 40, height = 40) {
                    property("value", String.format(Locale.ROOT, "%.2f", factory.energy))
                    property("label", "Energie")
                }
                text(if (factory.running) "läuft" else "aus", y = 16, color = if (factory.running) Color.LIME else Color.GRAY, id = "state")
            }

            panel(width = INNER, height = 20, id = "counter_row") {
                layout = Layout.Row(gap = 4, padding = 0)
                text("Zähler: ${factory.counter}", y = 6, width = 70, id = "counter")
                button("increment", "+1", width = 30) {
                    onClick { ctx ->
                        factory.counter++
                        ctx.screen.refresh()
                    }
                }
                button("reset", "Reset", width = 50) {
                    enabled = factory.counter > 0
                    onClick { ctx ->
                        factory.counter = 0
                        ctx.screen.refresh()
                    }
                }
            }

            textField("machine_name", width = INNER, height = 18) {
                value = initialName
                maxLength = 32
                placeholder = Component.text("Name der Maschine", NamedTextColor.DARK_GRAY)
                onInput { ctx ->
                    factory.machineName = ctx.value
                    ctx.screen.refresh()
                }
            }
            text("Maschine: ${factory.machineName.ifBlank { "(ohne Namen)" }}", width = INNER, color = Color.fromRGB(0xAADDFF), id = "name_display")

            panel(width = INNER, height = 18, id = "items") {
                layout = Layout.Row(gap = 8, padding = 0)
                text("Input", y = 5, width = 30, id = "input_label")
                if (factory.input > 0) item(Key.key("iron_ore"), count = factory.input.coerceAtMost(64), id = "input")
                text("→", y = 5, width = 10, align = TextAlign.CENTER, id = "arrow")
                if (factory.output > 0) item(Key.key("iron_ingot"), count = factory.output.coerceAtMost(64), id = "output")
                text("Output: ${factory.output}", y = 5, width = 70, id = "output_label")
            }

            onClose { ctx ->
                if (openFactories[ctx.player.uniqueId] === ctx.screen) openFactories.remove(ctx.player.uniqueId)
                if (ctx.reason == CloseReason.PLAYER) {
                    ctx.player.sendMessage(Component.text("Factory geschlossen (läuft weiter).", NamedTextColor.GRAY))
                }
            }
        }
    }

    // ---- command --------------------------------------------------------------------------------------------

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage("Nur für Spieler.")
            return true
        }
        when (args.firstOrNull()?.lowercase() ?: "open") {
            "open" -> openFactory(player)
            "close" -> player.currentKilnScreen?.close()
                ?: player.sendMessage(Component.text("Keine Kiln-GUI offen.", NamedTextColor.GRAY))
            "status" -> {
                val bridge = Kiln.get()
                val factory = factories[player.uniqueId]
                player.sendMessage(
                    Component.text(
                        "Erweiterungen: ${bridge.extensions().joinToString { "${it.id} ${it.version}" }.ifEmpty { "-" }}" +
                            (factory?.let { ", Factory '${it.machineName}': Energie ${(it.energy * 100).toInt()} %, Output ${it.output}" } ?: ""),
                        NamedTextColor.GREEN,
                    ),
                )
            }
            else -> player.sendMessage(Component.text("Benutzung: /pbdemo [open|close|status]", NamedTextColor.RED))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> =
        if (args.size == 1) SUBCOMMANDS.filter { it.startsWith(args[0].lowercase()) } else emptyList()

    // ---- events ---------------------------------------------------------------------------------------------

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        event.player.sendMessage(
            Component.text("G öffnet die Factory, H pingt, J grüßt per Lua-Erweiterung, K oder /shop öffnet den Markt (Lua), /shopdsl, /pbdemo für mehr.", NamedTextColor.GREEN),
        )
        event.player.sendMessage(
            Component.text("ui_kit-Demos: B /fabrik, U /quests, V /warps, N /bestenliste, M /profil.", NamedTextColor.GREEN),
        )
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        factories.remove(uuid)
        openFactories.remove(uuid)
        pingPressedAt.remove(uuid)
    }

    private companion object {
        const val WIDTH = 220
        const val HEIGHT = 196
        const val INNER = WIDTH - 12
        val SUBCOMMANDS = listOf("open", "close", "status")
    }
}
