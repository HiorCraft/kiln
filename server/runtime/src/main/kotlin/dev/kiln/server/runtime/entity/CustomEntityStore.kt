package dev.kiln.server.runtime.entity

import java.util.UUID

/** Line format of the chunk entry `kiln:entities`: `type;x;y;z;yaw;health;state;uuid` per line; invalid lines are skipped. */
object CustomEntityStore {
    /**
     * Entries of entities that already live elsewhere are stale copies (an older save) and are dropped; entries of
     * types that cannot spawn (not registered) are kept.
     */
    fun plan(entries: List<StoredEntity>, isLive: (UUID) -> Boolean, canSpawn: (StoredEntity) -> Boolean): RestorePlan {
        val spawn = ArrayList<StoredEntity>()
        val keep = ArrayList<StoredEntity>()
        for (entry in entries) when {
            isLive(entry.id) -> {}
            canSpawn(entry) -> spawn += entry
            else -> keep += entry
        }
        return RestorePlan(spawn, keep)
    }

    fun encode(list: List<StoredEntity>): String =
        list.joinToString("\n") { "${it.type};${it.x};${it.y};${it.z};${it.yaw};${it.health};${it.state};${it.id}" }

    fun decode(text: String): DecodedEntities {
        val result = ArrayList<StoredEntity>()
        var invalid = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val p = line.split(';')
            val entry = if (p.size != 8 || ':' !in p[0] || !EntityNames.isState(p[6])) null else try {
                StoredEntity(p[0], p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toFloat(), p[5].toDouble(), p[6], UUID.fromString(p[7]))
            } catch (e: IllegalArgumentException) {
                null
            }
            if (entry == null) invalid++ else result += entry
        }
        return DecodedEntities(result, invalid)
    }
}
