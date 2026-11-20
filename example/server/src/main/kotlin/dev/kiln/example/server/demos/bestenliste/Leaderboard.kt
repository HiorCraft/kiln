package dev.kiln.example.server.demos.bestenliste

import com.google.gson.JsonObject
import dev.kiln.example.server.demos.DemoResult
import dev.kiln.example.server.demos.RequestGuard
import dev.kiln.example.server.shop.lua.RequestFields
import java.util.UUID
import kotlin.random.Random

/*
 * Bestenliste (extension `bestenliste`): statistics per category and period. Demo players are simulated (they gain
 * a little every tick); real players count blocks/kills/deaths/distance from events, their total play time comes
 * from the Bukkit statistic.
 *
 * C->S  query {req, category, period}   selects what the state shows (validated against the enums)
 */

class Leaderboard(seed: Long = 42, private val guard: RequestGuard = RequestGuard()) {
    private val random = Random(seed)
    private val entries = LinkedHashMap<String, LeaderboardEntry>()
    private val selection = HashMap<UUID, Pair<StatCategory, StatPeriod>>()

    init {
        for (name in DEMO_PLAYERS) {
            val e = LeaderboardEntry(name)
            for (c in StatCategory.entries) {
                val total = when (c) {
                    StatCategory.BLOCKS -> random.nextLong(500, 60_000)
                    StatCategory.KILLS -> random.nextLong(20, 4_000)
                    StatCategory.PLAYTIME -> random.nextLong(60, 30_000)
                    StatCategory.DISTANCE -> random.nextLong(2_000, 900_000)
                    StatCategory.DEATHS -> random.nextLong(0, 400)
                }
                val week = total * random.nextInt(2, 25) / 100
                val day = week * random.nextInt(5, 40) / 100
                e.values[c.ordinal][StatPeriod.ALL.ordinal] = total
                e.values[c.ordinal][StatPeriod.WEEK.ordinal] = week
                e.values[c.ordinal][StatPeriod.DAY.ordinal] = day
            }
            entries[name] = e
        }
        snapshot()
    }

    private fun entry(name: String) = entries.getOrPut(name) { LeaderboardEntry(name) }

    /** Adds [amount] to every period of [category] (real players' events). */
    fun record(name: String, category: StatCategory, amount: Long) {
        if (amount <= 0) return
        val v = entry(name).values[category.ordinal]
        for (p in StatPeriod.entries) v[p.ordinal] += amount
    }

    /** Sets the all-time value of [category] (from the game's own statistics), never below the counted value. */
    fun setTotal(name: String, category: StatCategory, value: Long) {
        val v = entry(name).values[category.ordinal]
        v[StatPeriod.ALL.ordinal] = maxOf(v[StatPeriod.ALL.ordinal], value, v[StatPeriod.WEEK.ordinal])
    }

    /** Simulates the demo players: a few of them gain something in a random category. */
    fun simulate() {
        for (name in DEMO_PLAYERS) {
            if (random.nextInt(3) != 0) continue
            val c = StatCategory.entries[random.nextInt(StatCategory.entries.size)]
            val gain = when (c) {
                StatCategory.BLOCKS -> random.nextLong(1, 40)
                StatCategory.KILLS -> random.nextLong(1, 4)
                StatCategory.PLAYTIME -> 1
                StatCategory.DISTANCE -> random.nextLong(5, 200)
                StatCategory.DEATHS -> if (random.nextInt(10) == 0) 1 else 0
            }
            record(name, c, gain)
        }
    }

    /** Starts a new trend interval (gain = value now - value at the last snapshot). */
    fun snapshot() {
        for (e in entries.values) for (c in StatCategory.entries) e.values[c.ordinal].copyInto(e.snapshot[c.ordinal])
    }

    fun selection(player: UUID): Pair<StatCategory, StatPeriod> = selection[player] ?: (StatCategory.BLOCKS to StatPeriod.ALL)

    fun state(player: UUID, name: String): BoardState {
        val (c, p) = selection(player)
        entry(name)
        val sorted = entries.values.sortedWith(compareByDescending<LeaderboardEntry> { it.get(c, p) }.thenBy { it.name.lowercase() })
        val rows = ArrayList<RowDto>()
        var rank = 0
        var previous = Long.MIN_VALUE
        sorted.forEachIndexed { i, e ->
            val value = e.get(c, p)
            if (value != previous) rank = i + 1
            previous = value
            if (i < TOP || e.name == name) {
                rows += RowDto(rank, e.name, value, value - e.snapshot[c.ordinal][p.ordinal], e.name == name)
            }
        }
        return BoardState(
            StatCategory.entries.map { LabelDto(it.name, it.label, it.unit) },
            StatPeriod.entries.map { LabelDto(it.name, it.label) },
            c.name, p.name, rows, entries.size,
        )
    }

    fun query(player: UUID, msg: JsonObject?, now: Long): DemoResult = guard.run(player, msg, "query", now) { req ->
        val c = RequestFields.string(msg, "category", 16)?.let { id -> StatCategory.entries.find { it.name == id } }
        val p = RequestFields.string(msg, "period", 16)?.let { id -> StatPeriod.entries.find { it.name == id } }
        if (c == null || p == null) return@run DemoResult.fail(req, "query", DemoResult.INVALID)
        selection[player] = c to p
        DemoResult.ok(req, "query")
    }

    fun forget(player: UUID) {
        selection.remove(player)
        guard.forget(player)
    }

    companion object {
        const val TOP = 50

        val DEMO_PLAYERS = listOf(
            "Alex", "Greta", "Hans", "Jonas", "Lena", "Mia", "Noah", "Paul", "Sophie", "Tim", "Clara", "Emil", "Frieda",
            "Karl", "Lotte", "Moritz", "Nele", "Oskar", "Pia", "Quentin", "Rosa", "Theo", "Uwe", "Wilma",
        )
    }
}
