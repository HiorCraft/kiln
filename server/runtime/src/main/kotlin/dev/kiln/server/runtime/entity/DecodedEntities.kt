package dev.kiln.server.runtime.entity

/** Entries read from a chunk, and how many lines were invalid. */
data class DecodedEntities(val entries: List<StoredEntity>, val invalid: Int)
