package dev.kiln.example.server.demos.profil

import dev.kiln.example.server.demos.LuaDemo
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/** `/profil` and keybind M: Einstellungen/Profil (Lua extension `profil`); applies nickname and color to the tab list. */
class ProfileDemo(plugin: JavaPlugin) : LuaDemo(plugin, "profil") {
    private val profiles = ProfileSettings()

    init {
        request("save") { player, msg ->
            val outcome = profiles.save(player.uniqueId, msg, System.currentTimeMillis())
            outcome.settings?.let { apply(player, it) }
            outcome.result
        }
    }

    private fun apply(player: Player, settings: Settings) {
        val color = ProfileSettings.COLORS.find { it.id == settings.nickColor } ?: return
        val name = Component.text(settings.nickname.ifEmpty { player.name }, TextColor.color(color.rgb.toInt()))
        player.displayName(name)
        player.playerListName(name)
    }

    override fun state(player: Player): Any = profiles.state(player.uniqueId, player.name)

    override fun forget(player: UUID) = profiles.forget(player)
}
