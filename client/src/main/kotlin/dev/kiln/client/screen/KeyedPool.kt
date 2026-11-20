package dev.kiln.client.screen

/**
 * Keeps one stateful object (vanilla widget, widget context, ...) per element id across tree updates.
 * Objects whose id survives an update are reused (so focus, cursor and text survive), new ids get a new object,
 * vanished ids are disposed.
 */
class KeyedPool<S, W>(
    private val idOf: (S) -> String,
    private val create: (S) -> W,
    private val update: (W, S) -> Unit,
    private val dispose: (W) -> Unit = {},
    /** Whether an existing object can represent the new spec (e.g. same element kind); otherwise it is recreated. */
    private val compatible: (W, S) -> Boolean = { _, _ -> true },
) {
    private val entries = LinkedHashMap<String, W>()

    val size: Int get() = entries.size

    operator fun get(id: String): W? = entries[id]

    val values: Collection<W> get() = entries.values

    /** Applies [specs] (in order); returns the objects in the order of [specs]. */
    fun sync(specs: List<S>): List<W> {
        val next = LinkedHashMap<String, W>()
        for (spec in specs) {
            val id = idOf(spec)
            if (id in next) continue // ids are unique per tree; be defensive anyway
            val existing = entries.remove(id)
            val target = if (existing != null && compatible(existing, spec)) {
                existing
            } else {
                existing?.let(dispose)
                create(spec)
            }
            update(target, spec)
            next[id] = target
        }
        entries.values.forEach(dispose)
        entries.clear()
        entries.putAll(next)
        return next.values.toList()
    }

    fun clear() {
        entries.values.forEach(dispose)
        entries.clear()
    }
}
