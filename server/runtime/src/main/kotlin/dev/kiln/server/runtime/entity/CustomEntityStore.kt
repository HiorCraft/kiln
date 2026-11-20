package dev.kiln.server.runtime.entity

import java.util.UUID

/** A custom entity as stored in its chunk while it is not loaded. */
data class StoredEntity(
    val type: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val health: Double, val state: String, val id: UUID,
)

/** Line format of the chunk entry `kiln:entities`: `type;x;y;z;yaw;health;state;uuid` per line; invalid lines are skipped. */
object CustomEntityStore {
    data class Decoded(val entries: List<StoredEntity>, val invalid: Int)

    /** What a chunk load does with its entries: [spawn] them, [keep] the rest stored. */
    data class Plan(val spawn: List<StoredEntity>, val keep: List<StoredEntity>)

    /**
     * Entries of entities that already live elsewhere are stale copies (an older save) and are dropped; entries of
     * types that cannot spawn (not registered) are kept.
     */
    fun plan(entries: List<StoredEntity>, isLive: (java.util.UUID) -> Boolean, canSpawn: (StoredEntity) -> Boolean): Plan {
        val spawn = ArrayList<StoredEntity>()
        val keep = ArrayList<StoredEntity>()
        for (entry in entries) when {
            isLive(entry.id) -> {}
            canSpawn(entry) -> spawn += entry
            else -> keep += entry
        }
        return Plan(spawn, keep)
    }

    fun encode(list: List<StoredEntity>): String =
        list.joinToString("\n") { "${it.type};${it.x};${it.y};${it.z};${it.yaw};${it.health};${it.state};${it.id}" }

    fun decode(text: String): Decoded {
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
        return Decoded(result, invalid)
    }
}

/** Names that are stored or sent: behaviour states (chunk data) and animations (protocol). */
object EntityNames {
    private val STATE = Regex("[A-Za-z0-9_.-]{1,64}")
    private val ANIMATION = Regex("[a-z0-9_.]{1,64}")

    fun isState(name: String): Boolean = STATE.matches(name)

    fun state(name: String): String {
        require(isState(name)) { "State name '$name' must match [A-Za-z0-9_.-]{1,64}" }
        return name
    }

    fun animation(name: String): String {
        require(ANIMATION.matches(name)) { "Animation name '$name' must match [a-z0-9_.]{1,64}" }
        return name
    }

    fun speed(speed: Float): Float = if (speed.isNaN()) 0f else speed.coerceIn(0f, 8f)
}
