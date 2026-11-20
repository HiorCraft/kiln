package dev.kiln.example.server.demos.bestenliste

import dev.kiln.example.server.demos.DemoResult
import dev.kiln.example.server.demos.LuaDemo
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/** `/bestenliste` and keybind N: Statistik-/Bestenliste (Lua extension `bestenliste`), refreshed every 5 s. */
class LeaderboardDemo(plugin: JavaPlugin) : LuaDemo(plugin, "bestenliste") {
    private val board = Leaderboard()

    /** Last read game statistics per player: play minutes, distance in m (deltas count for day/week). */
    private val lastStats = HashMap<UUID, LongArray>()
    private var ticks = 0

    init {
        request("query") { player, msg -> board.query(player.uniqueId, msg, System.currentTimeMillis()) }
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 100L, 100L)
    }

    private fun tick() {
        ticks++
        board.simulate()
        for (player in Bukkit.getOnlinePlayers()) readStatistics(player)
        if (ticks % 12 == 0) board.snapshot() // trend = gain within the last minute
        pushAll()
    }

    private fun readStatistics(player: Player) {
        val minutes = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200L
        val meters = (player.getStatistic(Statistic.WALK_ONE_CM) + player.getStatistic(Statistic.SPRINT_ONE_CM)) / 100L
        val last = lastStats[player.uniqueId]
        if (last != null) {
            board.record(player.name, StatCategory.PLAYTIME, minutes - last[0])
            board.record(player.name, StatCategory.DISTANCE, meters - last[1])
        }
        lastStats[player.uniqueId] = longArrayOf(minutes, meters)
        board.setTotal(player.name, StatCategory.PLAYTIME, minutes)
        board.setTotal(player.name, StatCategory.DISTANCE, meters)
        board.setTotal(player.name, StatCategory.KILLS, player.getStatistic(Statistic.MOB_KILLS).toLong())
        board.setTotal(player.name, StatCategory.DEATHS, player.getStatistic(Statistic.DEATHS).toLong())
    }

    override fun afterRequest(player: Player, result: DemoResult) {
        // the answer to a query is the state with the new selection
        if (result.ok) push(player)
    }

    override fun state(player: Player): Any = board.state(player.uniqueId, player.name)

    override fun forget(player: UUID) {
        board.forget(player)
        lastStats.remove(player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) = board.record(event.player.name, StatCategory.BLOCKS, 1)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onKill(event: EntityDeathEvent) {
        if (event is PlayerDeathEvent) {
            board.record(event.player.name, StatCategory.DEATHS, 1)
            return
        }
        event.entity.killer?.let { board.record(it.name, StatCategory.KILLS, 1) }
    }
}
