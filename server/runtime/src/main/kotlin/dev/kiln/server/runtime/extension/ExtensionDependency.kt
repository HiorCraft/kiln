package dev.kiln.server.runtime.extension

/**
 * A dependency of an extension on another one (`extension.json` `dependencies`): the [id] it imports via
 * `kiln.import(id)` and an optional version [range].
 */
data class ExtensionDependency(val id: String, val range: VersionRange? = null) {
    override fun toString(): String = if (range == null) id else "$id $range"
}
