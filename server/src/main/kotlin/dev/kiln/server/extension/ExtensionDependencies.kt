package dev.kiln.server.extension

/**
 * A dependency of an extension on another one (`extension.json` `dependencies`): the [id] it imports via
 * `kiln.import(id)` and an optional version [range].
 */
data class ExtensionDependency(val id: String, val range: VersionRange? = null) {
    override fun toString(): String = if (range == null) id else "$id $range"
}

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

/**
 * Resolves the delivery (= load) order of extension packages: every dependency before its dependents, otherwise
 * registration order (stable topological sort, Kahn). Pure function.
 */
object ExtensionDependencies {
    /** [order] in load order, or every [problems] found (missing dependency, version mismatch, cycle). */
    data class Resolution(val order: List<ExtensionPackage>, val problems: List<String>) {
        val ok: Boolean get() = problems.isEmpty()
    }

    fun resolve(packages: List<ExtensionPackage>): Resolution {
        val byId = packages.associateBy { it.id }
        val problems = ArrayList<String>()
        for (pkg in packages) {
            for (dep in pkg.dependencies) {
                val target = byId[dep.id]
                when {
                    target == null -> problems += "Extension '${pkg.id}' needs '${dep.id}', which is not registered"
                    dep.range != null && !dep.range.matches(target.version) ->
                        problems += "Extension '${pkg.id}' needs '${dep.id}' ${dep.range}, but version ${target.version} is registered"
                }
            }
        }
        if (problems.isNotEmpty()) return Resolution(emptyList(), problems)

        val remaining = LinkedHashMap<String, Int>()
        for (pkg in packages) remaining[pkg.id] = pkg.dependencies.size
        val order = ArrayList<ExtensionPackage>(packages.size)
        val done = HashSet<String>()
        while (order.size < packages.size) {
            // earliest registered package whose dependencies are all placed
            val next = packages.firstOrNull { it.id !in done && it.dependencies.all { d -> d.id in done } } ?: break
            order += next
            done += next.id
        }
        if (order.size < packages.size) {
            val stuck = packages.filter { it.id !in done }
            problems += "Dependency cycle between extensions: ${cycle(stuck)}"
            return Resolution(emptyList(), problems)
        }
        return Resolution(order, emptyList())
    }

    /** One cycle among [stuck] packages as `a -> b -> a` (for the error message). */
    private fun cycle(stuck: List<ExtensionPackage>): String {
        val byId = stuck.associateBy { it.id }
        var current = stuck.first()
        val path = ArrayList<String>()
        while (current.id !in path) {
            path += current.id
            current = current.dependencies.firstNotNullOfOrNull { byId[it.id] } ?: break
        }
        val start = path.indexOf(current.id).coerceAtLeast(0)
        return (path.drop(start) + current.id).joinToString(" -> ")
    }
}
