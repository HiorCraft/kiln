package dev.kiln.client.screen

/** Item count text for slots: plain up to 999, then abbreviated (`1.2k`, `12k`, `999k`, `1.5M`, `1B`), never rounded up. */
object CountFormat {
    fun format(count: Int): String = when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> scaled(count, 1_000, "k")
        count < 1_000_000_000 -> scaled(count, 1_000_000, "M")
        else -> scaled(count, 1_000_000_000, "B")
    }

    private fun scaled(count: Int, unit: Int, suffix: String): String {
        val whole = count / unit
        if (whole >= 10) return "$whole$suffix"
        val tenths = (count / (unit / 10)) % 10
        return if (tenths == 0) "$whole$suffix" else "$whole.$tenths$suffix"
    }

    /** Text for the slot decoration: `null` (nothing drawn) for a count of 1. */
    fun decoration(count: Int): String? = if (count == 1) null else format(count)
}
