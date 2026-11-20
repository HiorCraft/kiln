package dev.kiln.server.runtime.extension

/** What joining players get: [packages] in load order, or [problems] (then nobody can join). */
data class ExtensionDelivery(val packages: List<ExtensionPackage>, val problems: List<String>)
