package dev.kiln.example.server

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.plugin.java.JavaPlugin

/**
 * Paper plugins declare no commands in their YAML: every demo command is registered through Paper's Brigadier
 * command API ([JavaPlugin.registerCommand]). The demos keep their [TabExecutor] logic; [BasicCommand] adapts it.
 */
internal object ExampleCommands {
    /** label -> (description, usage) as the old `plugin.yml` had them. */
    val DESCRIPTIONS: Map<String, Pair<String, String>> = linkedMapOf(
        "pbdemo" to ("Opens the kiln factory demo GUI" to "/pbdemo [open|close|status]"),
        "shop" to ("Opens the market (Lua extension shop with ui_kit)" to "/shop [markt|verkaufen|angebote|balance]"),
        "shopdsl" to ("Opens the same market rendered by the server (screen DSL)" to "/shopdsl [balance]"),
        "fabrik" to ("Fabrik-Steuerzentrale (Lua extension fabrik, key B)" to "/fabrik"),
        "quests" to ("Quest-Log (Lua extension questlog, key U)" to "/quests [open|test]"),
        "warps" to ("Warp menu (Lua extension warps, key V)" to "/warps"),
        "bestenliste" to ("Leaderboard (Lua extension bestenliste, key N)" to "/bestenliste"),
        "profil" to ("Settings and profile (Lua extension profil, key M)" to "/profil"),
        "kiln" to ("Content demo: custom item, sound, font, enchantment, post effect" to "/kiln give ruby|lifesteal_sword|chair|table|bench | spawn golem | font | effect [ruby_vision|off]"),
    )

    fun register(plugin: JavaPlugin, label: String, executor: TabExecutor) {
        val (description, usage) = DESCRIPTIONS[label] ?: error("unknown demo command $label")
        val command = object : Command(label, description, usage, emptyList()) {
            override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean = false
        }
        plugin.registerCommand(label, description, object : BasicCommand {
            override fun execute(source: CommandSourceStack, args: Array<out String>) {
                if (!executor.onCommand(source.sender, command, label, args)) source.sender.sendMessage("Usage: $usage")
            }

            override fun suggest(source: CommandSourceStack, args: Array<out String>): Collection<String> =
                executor.onTabComplete(source.sender, command, label, args) ?: emptyList()
        })
    }
}
