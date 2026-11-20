package dev.kiln.example.server.demos.questlog

import dev.kiln.server.api.extension.ExtensionItems
import dev.kiln.server.api.showToast
import dev.kiln.example.server.demos.LuaDemo
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/** `/quests` and keybind U: Quest-Log (Lua extension `questlog`); progress from broken blocks and killed mobs. */
class QuestDemo(plugin: JavaPlugin) : LuaDemo(plugin, "questlog") {
    private val book = QuestBook()
    private val encoded = HashMap<String, String?>()

    init {
        request("accept") { player, msg -> book.accept(player.uniqueId, msg, System.currentTimeMillis()) }
        request("abandon") { player, msg -> book.abandon(player.uniqueId, msg, System.currentTimeMillis()) }
    }

    private fun encode(key: String): String? = encoded.getOrPut(key) {
        Material.matchMaterial(key)?.takeIf { it.isItem }?.let { runCatching { ExtensionItems.encode(ItemStack(it)) }.getOrNull() }
    }

    override fun state(player: Player): Any = book.state(player.uniqueId, ::encode)

    override fun forget(player: UUID) = book.forget(player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        record(event.player, ObjectiveKind.BREAK, event.block.type.key.asString())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKill(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        record(killer, ObjectiveKind.KILL, event.entityType.key.asString())
    }

    private fun record(player: Player, kind: ObjectiveKind, id: String) {
        val (changed, done) = book.record(player.uniqueId, kind, id)
        complete(player, done)
        if (changed) push(player)
    }

    private fun complete(player: Player, done: List<Completion>) {
        for ((quest) in done) {
            for (reward in quest.rewards) {
                val material = Material.matchMaterial(reward.item) ?: continue
                val left = player.inventory.addItem(ItemStack(material, reward.count))
                left.values.forEach { player.world.dropItemNaturally(player.location, it) }
            }
            player.giveExp(quest.xp)
            player.showToast(
                Component.text("Quest abgeschlossen!", NamedTextColor.GOLD),
                Component.text(quest.title),
                quest.rewards.firstOrNull()?.let { Material.matchMaterial(it.item) }?.let { ItemStack(it) },
            )
        }
    }

    override fun command(player: Player, arg: String?) {
        when (arg) {
            null, "open" -> open(player)
            "test" -> {
                // demo helper: progress without playing (every active quest's next objective +5)
                complete(player, book.advance(player.uniqueId, 5))
                push(player)
                player.sendMessage(Component.text("Fortschritt aller aktiven Quests erhöht.", NamedTextColor.GRAY))
            }
            else -> player.sendMessage(Component.text("Benutzung: /quests [open|test]", NamedTextColor.RED))
        }
    }

    override fun completions(): List<String> = listOf("open", "test")
}
