package dev.kiln.server.api.screen

/** Hands out element ids for one render pass and rejects invalid or duplicate explicit ids. */
internal class IdAllocator {
    private val used = HashSet<String>().apply { add(ScreenBuilder.ROOT_ID) }
    private var next = 0

    fun explicit(id: String): String {
        require(ID.matches(id)) { "Invalid element id '$id' (allowed: [A-Za-z0-9_.:/-], 1..64 chars)" }
        require(!id.startsWith(AUTO_PREFIX)) { "Element ids starting with '$AUTO_PREFIX' are reserved" }
        require(used.add(id)) { "Duplicate element id '$id'" }
        return id
    }

    fun passive(id: String?): String {
        if (id != null) return explicit(id)
        val auto = AUTO_PREFIX + next++
        used += auto
        return auto
    }

    private companion object {
        const val AUTO_PREFIX = "auto:"
        val ID = Regex("[A-Za-z0-9_.:/-]{1,${ScreenLimits.MAX_ID_LENGTH}}")
    }
}
