package dev.kiln.server.runtime.item

/** What content registrations need to know about an extension: who registered it and which files it has. */
data class ContentExtension(val ownerName: String, val files: Set<String>)
