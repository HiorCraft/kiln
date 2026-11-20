package dev.kiln.client.keybind

/** Detects press/release transitions of boolean key states sampled once per tick. */
class KeyEdgeTracker {
    private val down = HashMap<String, Boolean>()

    /** @return `true` = pressed edge, `false` = released edge, `null` = no change */
    fun update(id: String, isDown: Boolean): Boolean? {
        val previous = down.put(id, isDown) ?: false
        return if (previous == isDown) null else isDown
    }

    fun forget(id: String) {
        down.remove(id)
    }

    fun clear() = down.clear()
}
