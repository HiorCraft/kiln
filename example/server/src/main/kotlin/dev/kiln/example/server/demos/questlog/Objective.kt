package dev.kiln.example.server.demos.questlog

/** [targets]: namespaced ids; an entry `*_log` matches every id ending in `_log`. */
data class Objective(val kind: ObjectiveKind, val targets: List<String>, val count: Int, val label: String) {
    fun matches(kind: ObjectiveKind, id: String): Boolean =
        kind == this.kind && targets.any { t -> if (t.startsWith("*")) id.endsWith(t.substring(1)) else id == t }
}
