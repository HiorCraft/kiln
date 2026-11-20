package dev.kiln.server.runtime.extension

/**
 * A version requirement: whitespace-separated comparators that must all hold, e.g. `>=1.2 <2`, `=1.0.0`, `1.0.0`
 * (bare version = `=`). Operators: `>=`, `>`, `<=`, `<`, `=`. Versions are compared segment by segment (split at
 * `.`, `-`, `+`); numeric segments numerically, others as text; missing segments count as `0` (`1.2` = `1.2.0`).
 */
class VersionRange private constructor(private val text: String, private val comparators: List<Pair<String, String>>) {
    fun matches(version: String): Boolean = comparators.all { (op, v) ->
        val c = compare(version, v)
        when (op) {
            ">=" -> c >= 0
            ">" -> c > 0
            "<=" -> c <= 0
            "<" -> c < 0
            else -> c == 0
        }
    }

    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is VersionRange && other.text == text

    override fun hashCode(): Int = text.hashCode()

    companion object {
        private val COMPARATOR = Regex("(>=|<=|>|<|=)?([0-9A-Za-z.+-]{1,64})")

        /** @throws IllegalArgumentException for an empty or malformed range */
        fun parse(text: String): VersionRange {
            val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            require(tokens.isNotEmpty() && tokens.size <= 8) { "version range must have 1..8 comparators: '$text'" }
            val comparators = tokens.map { token ->
                val match = COMPARATOR.matchEntire(token) ?: throw IllegalArgumentException("invalid version comparator '$token' in '$text'")
                (match.groupValues[1].ifEmpty { "=" }) to match.groupValues[2]
            }
            return VersionRange(tokens.joinToString(" "), comparators)
        }

        /** Compares two version strings (see class doc). */
        fun compare(a: String, b: String): Int {
            val x = a.split('.', '-', '+')
            val y = b.split('.', '-', '+')
            for (i in 0 until maxOf(x.size, y.size)) {
                val p = x.getOrElse(i) { "0" }
                val q = y.getOrElse(i) { "0" }
                val pn = p.toLongOrNull()
                val qn = q.toLongOrNull()
                val c = if (pn != null && qn != null) pn.compareTo(qn) else p.compareTo(q)
                if (c != 0) return c.coerceIn(-1, 1)
            }
            return 0
        }
    }
}
