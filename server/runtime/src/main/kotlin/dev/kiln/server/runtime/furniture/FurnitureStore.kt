package dev.kiln.server.runtime.furniture

import java.util.UUID

/** Line format of the chunk entry `kiln:furniture`: `type;x;y;z;yaw;uuid` per line. Invalid lines are skipped. */
object FurnitureStore {
    fun encode(list: List<StoredFurniture>): String =
        list.joinToString("\n") { "${it.type};${it.x};${it.y};${it.z};${it.yaw};${it.id}" }

    fun decode(text: String): DecodedFurniture {
        val result = ArrayList<StoredFurniture>()
        var invalid = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split(';')
            val entry = if (parts.size != 6 || ':' !in parts[0]) null else try {
                StoredFurniture(parts[0], parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble(), parts[4].toFloat(), UUID.fromString(parts[5]))
            } catch (e: IllegalArgumentException) {
                null
            }
            if (entry == null) invalid++ else result += entry
        }
        return DecodedFurniture(result, invalid)
    }
}
