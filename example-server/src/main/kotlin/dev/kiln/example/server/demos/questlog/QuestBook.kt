package dev.kiln.example.server.demos.questlog

import com.google.gson.JsonObject
import dev.kiln.example.server.demos.DemoResult
import dev.kiln.example.server.demos.RequestGuard
import dev.kiln.example.server.shop.lua.RequestFields
import java.util.UUID

/*
 * Quest-Log (extension `questlog`): a fixed quest catalog, progress per player in memory. Progress comes from real
 * events (blocks broken, mobs killed; see QuestDemo); rewards are given by the server when a quest is completed.
 *
 * C->S  accept  {req, quest}     available -> active (at most MAX_ACTIVE at once)
 * C->S  abandon {req, quest}     active -> available, progress is lost
 */

enum class ObjectiveKind { BREAK, KILL }

/** [targets]: namespaced ids; an entry `*_log` matches every id ending in `_log`. */
data class Objective(val kind: ObjectiveKind, val targets: List<String>, val count: Int, val label: String) {
    fun matches(kind: ObjectiveKind, id: String): Boolean =
        kind == this.kind && targets.any { t -> if (t.startsWith("*")) id.endsWith(t.substring(1)) else id == t }
}

data class Reward(val item: String, val count: Int)

data class Quest(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val objectives: List<Objective>,
    val rewards: List<Reward>,
    val xp: Int,
)

enum class QuestStatus { AVAILABLE, ACTIVE, COMPLETED }

data class ObjectiveDto(val label: String, val have: Int, val need: Int)

data class RewardDto(
    /** `ExtensionItems.encode` of the reward item; `null` if it could not be encoded (client shows the id). */
    val item: String?,
    val key: String,
    val count: Int,
)

data class QuestDto(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val status: String,
    val objectives: List<ObjectiveDto>,
    val rewards: List<RewardDto>,
    val xp: Int,
)

data class QuestState(val maxActive: Int, val quests: List<QuestDto>)

/** A quest that was just completed by an event: the service gives [Quest.rewards] and shows a toast. */
data class Completion(val quest: Quest)

class QuestBook(val quests: List<Quest> = CATALOG, private val guard: RequestGuard = RequestGuard()) {
    private class Progress(var status: QuestStatus = QuestStatus.AVAILABLE, var counts: IntArray = IntArray(0))

    private val players = HashMap<UUID, HashMap<String, Progress>>()

    private fun progress(player: UUID, quest: Quest): Progress = players.getOrPut(player) { HashMap() }.getOrPut(quest.id) { Progress() }

    fun status(player: UUID, questId: String): QuestStatus? = quests.find { it.id == questId }?.let { progress(player, it).status }

    fun counts(player: UUID, questId: String): List<Int> = quests.find { it.id == questId }?.let { progress(player, it).counts.toList() } ?: emptyList()

    private fun active(player: UUID): Int = quests.count { progress(player, it).status == QuestStatus.ACTIVE }

    /** [encode] turns an item id into its client form; `null` if impossible. */
    fun state(player: UUID, encode: (String) -> String?): QuestState = QuestState(
        MAX_ACTIVE,
        quests.map { q ->
            val p = progress(player, q)
            QuestDto(
                q.id, q.title, q.category, q.description, p.status.name,
                q.objectives.mapIndexed { i, o ->
                    val have = when (p.status) {
                        QuestStatus.COMPLETED -> o.count
                        QuestStatus.ACTIVE -> p.counts.getOrElse(i) { 0 }
                        QuestStatus.AVAILABLE -> 0
                    }
                    ObjectiveDto(o.label, have, o.count)
                },
                q.rewards.map { RewardDto(encode(it.item), it.item, it.count) },
                q.xp,
            )
        },
    )

    private fun quest(msg: JsonObject?): Quest? = RequestFields.string(msg, "quest", 32)?.let { id -> quests.find { it.id == id } }

    fun accept(player: UUID, msg: JsonObject?, now: Long): DemoResult = guard.run(player, msg, "accept", now) { req ->
        val quest = quest(msg) ?: return@run DemoResult.fail(req, "accept", "Diese Quest gibt es nicht.")
        val p = progress(player, quest)
        when (p.status) {
            QuestStatus.ACTIVE -> return@run DemoResult.fail(req, "accept", "Die Quest ist schon aktiv.")
            QuestStatus.COMPLETED -> return@run DemoResult.fail(req, "accept", "Die Quest ist bereits abgeschlossen.")
            QuestStatus.AVAILABLE -> {}
        }
        if (active(player) >= MAX_ACTIVE) {
            return@run DemoResult.fail(req, "accept", "Du hast schon $MAX_ACTIVE aktive Quests – brich zuerst eine ab.")
        }
        p.status = QuestStatus.ACTIVE
        p.counts = IntArray(quest.objectives.size)
        DemoResult.ok(req, "accept", "Quest angenommen: ${quest.title}")
    }

    fun abandon(player: UUID, msg: JsonObject?, now: Long): DemoResult = guard.run(player, msg, "abandon", now) { req ->
        val quest = quest(msg) ?: return@run DemoResult.fail(req, "abandon", "Diese Quest gibt es nicht.")
        val p = progress(player, quest)
        if (p.status != QuestStatus.ACTIVE) return@run DemoResult.fail(req, "abandon", "Die Quest ist nicht aktiv.")
        p.status = QuestStatus.AVAILABLE
        p.counts = IntArray(0)
        DemoResult.ok(req, "abandon", "Quest abgebrochen: ${quest.title}")
    }

    /**
     * Counts an event ([kind] on [id], e.g. a broken `minecraft:oak_log`) for every active quest of [player].
     * Returns whether anything changed and the quests completed by it.
     */
    fun record(player: UUID, kind: ObjectiveKind, id: String, amount: Int = 1): Pair<Boolean, List<Completion>> {
        val map = players[player] ?: return false to emptyList()
        var changed = false
        val done = ArrayList<Completion>()
        for (quest in quests) {
            val p = map[quest.id] ?: continue
            if (p.status != QuestStatus.ACTIVE) continue
            quest.objectives.forEachIndexed { i, o ->
                if (o.matches(kind, id) && p.counts[i] < o.count) {
                    p.counts[i] = minOf(o.count, p.counts[i] + amount)
                    changed = true
                }
            }
            if (quest.objectives.indices.all { p.counts[it] >= quest.objectives[it].count }) {
                p.status = QuestStatus.COMPLETED
                done += Completion(quest)
            }
        }
        return changed to done
    }

    /** Test helper (`/quests test`): adds [amount] to the first unfinished objective of every active quest. */
    fun advance(player: UUID, amount: Int): List<Completion> {
        val map = players[player] ?: return emptyList()
        val done = ArrayList<Completion>()
        for (quest in quests) {
            val p = map[quest.id]?.takeIf { it.status == QuestStatus.ACTIVE } ?: continue
            val i = quest.objectives.indices.firstOrNull { p.counts[it] < quest.objectives[it].count } ?: continue
            p.counts[i] = minOf(quest.objectives[i].count, p.counts[i] + amount)
            if (quest.objectives.indices.all { p.counts[it] >= quest.objectives[it].count }) {
                p.status = QuestStatus.COMPLETED
                done += Completion(quest)
            }
        }
        return done
    }

    fun forget(player: UUID) {
        players.remove(player)
        guard.forget(player)
    }

    companion object {
        const val MAX_ACTIVE = 3

        val CATALOG = listOf(
            Quest(
                "holzfaeller", "Holzfäller", "Sammeln",
                "Der Zimmermann braucht Nachschub. Fälle Bäume beliebiger Sorte und bring das Holz ins Lager.",
                listOf(Objective(ObjectiveKind.BREAK, listOf("*_log"), 16, "Baumstämme abbauen")),
                listOf(Reward("minecraft:bread", 8), Reward("minecraft:iron_axe", 1)), 20,
            ),
            Quest(
                "bergmann", "Tief im Berg", "Bergbau",
                "Grabe dich durch den Fels und sichere Kohle für die Schmelzöfen der Stadt.",
                listOf(
                    Objective(ObjectiveKind.BREAK, listOf("minecraft:stone", "minecraft:deepslate", "minecraft:cobblestone"), 32, "Stein abbauen"),
                    Objective(ObjectiveKind.BREAK, listOf("*coal_ore"), 8, "Kohleerz abbauen"),
                ),
                listOf(Reward("minecraft:iron_pickaxe", 1), Reward("minecraft:torch", 32)), 40,
            ),
            Quest(
                "zombiejagd", "Zombiejagd", "Kampf",
                "Nachts streifen Zombies um die Mauern. Sorge dafür, dass es weniger werden.",
                listOf(Objective(ObjectiveKind.KILL, listOf("minecraft:zombie", "minecraft:husk", "minecraft:drowned"), 5, "Zombies besiegen")),
                listOf(Reward("minecraft:golden_apple", 1), Reward("minecraft:arrow", 16)), 30,
            ),
            Quest(
                "nachtwache", "Nachtwache", "Kampf",
                "Halte eine ganze Nacht Wache und besiege alles, was sich nähert.",
                listOf(
                    Objective(ObjectiveKind.KILL, listOf("minecraft:skeleton", "minecraft:stray"), 3, "Skelette besiegen"),
                    Objective(ObjectiveKind.KILL, listOf("minecraft:spider", "minecraft:cave_spider"), 2, "Spinnen besiegen"),
                    Objective(ObjectiveKind.KILL, listOf("minecraft:creeper"), 1, "Creeper besiegen"),
                ),
                listOf(Reward("minecraft:shield", 1), Reward("minecraft:bow", 1), Reward("minecraft:cooked_beef", 12)), 60,
            ),
            Quest(
                "ernte", "Erntezeit", "Landwirtschaft",
                "Die Felder sind reif. Ernte Weizen, Karotten oder Kartoffeln für das Erntefest.",
                listOf(Objective(ObjectiveKind.BREAK, listOf("minecraft:wheat", "minecraft:carrots", "minecraft:potatoes"), 24, "Feldfrüchte ernten")),
                listOf(Reward("minecraft:cake", 1), Reward("minecraft:bone_meal", 16)), 25,
            ),
            Quest(
                "sandburg", "Sandburg", "Sammeln",
                "Der Glaser braucht Sand für neue Fenster. Am Strand gibt es reichlich davon.",
                listOf(Objective(ObjectiveKind.BREAK, listOf("minecraft:sand", "minecraft:red_sand"), 20, "Sand abbauen")),
                listOf(Reward("minecraft:glass", 16)), 15,
            ),
            Quest(
                "diamantfieber", "Diamantenfieber", "Bergbau",
                "Gerüchte über Diamanten in der Tiefe machen die Runde. Finde heraus, ob sie stimmen.",
                listOf(Objective(ObjectiveKind.BREAK, listOf("*diamond_ore"), 2, "Diamanterz abbauen")),
                listOf(Reward("minecraft:emerald", 8), Reward("minecraft:experience_bottle", 4)), 100,
            ),
            Quest(
                "blumen", "Blumenstrauß", "Sammeln",
                "Für die Hochzeit im Dorf fehlen noch Blumen. Pflücke Löwenzahn und Mohn.",
                listOf(
                    Objective(ObjectiveKind.BREAK, listOf("minecraft:dandelion"), 4, "Löwenzahn pflücken"),
                    Objective(ObjectiveKind.BREAK, listOf("minecraft:poppy"), 4, "Mohn pflücken"),
                ),
                listOf(Reward("minecraft:emerald", 2), Reward("minecraft:flower_pot", 2)), 10,
            ),
        )
    }
}
