package dev.kiln.server.runtime.entity

/** What a chunk load does with its entries: [spawn] them, [keep] the rest stored. */
data class RestorePlan(val spawn: List<StoredEntity>, val keep: List<StoredEntity>)
