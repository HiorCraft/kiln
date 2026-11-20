package dev.kiln.example.server.demos.fabrik

import dev.kiln.example.server.demos.LuaDemo
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/** `/fabrik` and keybind B: Fabrik-Steuerzentrale (Lua extension `fabrik`), simulated once per second. */
class FactoryDemo(plugin: JavaPlugin) : LuaDemo(plugin, "fabrik") {
    private val control = FactoryControl()

    init {
        request("set") { player, msg -> control.set(player.uniqueId, msg, System.currentTimeMillis()) }
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
    }

    private fun tick() {
        for (player in Bukkit.getOnlinePlayers()) {
            control.tick(player.uniqueId)
            push(player)
        }
    }

    override fun state(player: Player): Any = control.state(player.uniqueId)

    override fun forget(player: UUID) = control.forget(player)
}
