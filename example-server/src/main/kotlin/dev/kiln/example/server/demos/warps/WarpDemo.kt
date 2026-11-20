package dev.kiln.example.server.demos.warps

import dev.kiln.example.server.demos.DemoResult
import dev.kiln.example.server.demos.LuaDemo
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/** `/warps` and keybind V: Warp-Menü (Lua extension `warps`). Public warps lie around the spawn of the main world. */
class WarpDemo(plugin: JavaPlugin) : LuaDemo(plugin, "warps") {
    private val book = WarpBook(publicWarps())

    init {
        request("teleport") { player, msg -> teleport(player, msg) }
        request("favorite") { player, msg -> book.favorite(player.uniqueId, msg, System.currentTimeMillis()) }
        request("create") { player, msg -> book.create(player.uniqueId, msg, here(player), System.currentTimeMillis()) }
        request("delete") { player, msg -> book.delete(player.uniqueId, msg, System.currentTimeMillis()) }
    }

    private fun publicWarps(): List<Warp> {
        val world = Bukkit.getWorlds().first()
        val s = world.spawnLocation
        fun at(dx: Int, dz: Int) = WarpLocation(world.name, s.blockX + dx + 0.5, s.y, s.blockZ + dz + 0.5, surface = true)
        return listOf(
            Warp("spawn", "Spawn", "minecraft:red_bed", "Der Startpunkt der Welt.", WarpLocation(world.name, s.x, s.y, s.z, s.yaw, s.pitch)),
            Warp("markt", "Marktplatz", "minecraft:emerald", "Handel mit anderen Spielern (/shop).", at(40, 0)),
            Warp("arena", "Arena", "minecraft:iron_sword", "Kämpfe gegen Freunde und Monster.", at(-60, 30)),
            Warp("farm", "Gemeinschaftsfarm", "minecraft:wheat", "Felder für alle – bitte nachpflanzen.", at(0, 70)),
            Warp("mine", "Mine", "minecraft:iron_pickaxe", "Eingang zum Stollensystem.", at(-80, -80)),
            Warp("hafen", "Hafen", "minecraft:oak_boat", "Boote und Fernreisen.", at(120, -40)),
            Warp("turm", "Aussichtsturm", "minecraft:spyglass", "Der höchste Punkt der Gegend.", at(25, -110)),
            Warp("bibliothek", "Bibliothek", "minecraft:bookshelf", "Verzauberungen und Wissen.", at(-30, -25)),
        )
    }

    private fun here(player: Player): WarpLocation {
        val l = player.location
        return WarpLocation(l.world.name, l.x, l.y, l.z, l.yaw, l.pitch)
    }

    private fun teleport(player: Player, msg: com.google.gson.JsonObject): DemoResult {
        val outcome = book.teleport(player.uniqueId, msg, System.currentTimeMillis())
        val target = outcome.location ?: return outcome.result
        val world = Bukkit.getWorld(target.world)
        if (world == null) {
            book.cancelCooldown(player.uniqueId)
            return DemoResult.fail(outcome.result.req, "teleport", "Die Welt ${target.world} ist nicht geladen.")
        }
        val y = if (target.surface) world.getHighestBlockYAt(target.x.toInt(), target.z.toInt()) + 1.0 else target.y
        player.teleport(Location(world, target.x, y, target.z, target.yaw, target.pitch))
        return outcome.result
    }

    override fun state(player: Player): Any = book.state(player.uniqueId, System.currentTimeMillis())

    override fun forget(player: UUID) = book.forget(player)
}
