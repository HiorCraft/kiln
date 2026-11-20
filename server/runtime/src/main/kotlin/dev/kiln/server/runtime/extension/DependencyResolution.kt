package dev.kiln.server.runtime.extension

/** [order] in load order, or every [problems] found (missing dependency, version mismatch, cycle). */
data class DependencyResolution(val order: List<ExtensionPackage>, val problems: List<String>) {
    val ok: Boolean get() = problems.isEmpty()
}
