package dev.kiln.server.runtime.furniture

/** Entries read from a chunk, and how many lines were invalid. */
data class DecodedFurniture(val entries: List<StoredFurniture>, val invalid: Int)
