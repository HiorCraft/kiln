package dev.kiln.example.server.demos.bestenliste

/** One player's values per category and period, plus the values at the last trend snapshot. */
internal class LeaderboardEntry(val name: String) {
    val values = Array(StatCategory.entries.size) { LongArray(StatPeriod.entries.size) }
    val snapshot = Array(StatCategory.entries.size) { LongArray(StatPeriod.entries.size) }
    fun get(c: StatCategory, p: StatPeriod) = values[c.ordinal][p.ordinal]
}
