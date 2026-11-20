package dev.kiln.server.runtime.furniture

import java.util.UUID
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin

data class Vec(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y, z + o.z)
}

/** Axis-aligned box in world coordinates. */
data class Box(val minX: Double, val minY: Double, val minZ: Double, val maxX: Double, val maxY: Double, val maxZ: Double) {
    /** Rounded to 1e-6 (comparisons in tests and logs). */
    fun rounded(): Box {
        fun r(v: Double) = round(v * 1e6) / 1e6
        return Box(r(minX), r(minY), r(minZ), r(maxX), r(maxY), r(maxZ))
    }
}

/**
 * One hit box of a furniture type: an `Interaction` entity, square base [width], [height], its feet at [offset]
 * from the furniture origin in the "facing south" frame (+z = front). [solid] boxes collide like boats.
 */
data class Hitbox(val width: Double, val height: Double, val offset: Vec, val solid: Boolean)

/** Pure placement maths of furniture. */
object FurnitureGeometry {
    /** Yaw in 0..360; with hit boxes snapped to 90° steps (interaction boxes are axis-aligned). */
    fun snapYaw(yaw: Float, hasHitboxes: Boolean): Float {
        val normalized = ((yaw % 360f) + 360f) % 360f
        if (!hasHitboxes) return normalized
        return ((round(normalized / 90f) * 90f) % 360f)
    }

    /** [offset] rotated by Minecraft [yaw] (0 = south/+z, 90 = west/-x). */
    fun rotate(offset: Vec, yaw: Float): Vec {
        val rad = Math.toRadians(yaw.toDouble())
        val x = offset.x * cos(rad) - offset.z * sin(rad)
        val z = offset.x * sin(rad) + offset.z * cos(rad)
        fun clean(v: Double) = (round(v * 1e9) / 1e9).let { if (it == 0.0) 0.0 else it }
        return Vec(clean(x), offset.y, clean(z))
    }

    /** Feet position of [hitbox] for furniture at [origin] with [yaw]. */
    fun position(origin: Vec, yaw: Float, hitbox: Hitbox): Vec = origin + rotate(hitbox.offset, yaw)

    fun boxes(origin: Vec, yaw: Float, hitboxes: List<Hitbox>): List<Box> = hitboxes.map { box ->
        val feet = position(origin, yaw, box)
        val half = box.width / 2
        Box(feet.x - half, feet.y, feet.z - half, feet.x + half, feet.y + box.height, feet.z + half)
    }

    /**
     * After leaving a seat the rider is put on top of the furniture only when they got up: not when a teleport,
     * death or world change ended the ride (then they are elsewhere already).
     */
    fun shouldStandUp(alive: Boolean, sameWorld: Boolean, distanceSquared: Double): Boolean =
        alive && sameWorld && distanceSquared <= 4.0

    /** Indices of [seats] a rider sits on (rider seat positions within 0.05 blocks). */
    fun occupiedSeats(seats: List<Vec>, riders: List<Vec>): Set<Int> =
        seats.indices.filter { i ->
            riders.any { r -> kotlin.math.abs(r.x - seats[i].x) < 0.05 && kotlin.math.abs(r.y - seats[i].y) < 0.05 && kotlin.math.abs(r.z - seats[i].z) < 0.05 }
        }.toSet()

    /** Index of the unoccupied seat (world positions [seats]) closest to [point], or `null` if all are taken. */
    fun nearestFreeSeat(seats: List<Vec>, occupied: Set<Int>, point: Vec): Int? =
        seats.indices.filter { it !in occupied }.minByOrNull {
            val s = seats[it]
            (s.x - point.x) * (s.x - point.x) + (s.y - point.y) * (s.y - point.y) + (s.z - point.z) * (s.z - point.z)
        }

    /** `true` if a solid block cell intersects any box (touching faces is fine). */
    fun blocked(boxes: List<Box>, isSolidAt: (x: Int, y: Int, z: Int) -> Boolean): Boolean {
        val eps = 1e-6
        for (b in boxes) {
            for (x in floor(b.minX + eps).toInt() until ceil(b.maxX - eps).toInt())
                for (y in floor(b.minY + eps).toInt() until ceil(b.maxY - eps).toInt())
                    for (z in floor(b.minZ + eps).toInt() until ceil(b.maxZ - eps).toInt())
                        if (isSolidAt(x, y, z)) return true
        }
        return false
    }
}

/** One placed furniture as stored in its chunk. */
data class StoredFurniture(val type: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val id: UUID)

/** Line format of the chunk entry `kiln:furniture`: `type;x;y;z;yaw;uuid` per line. Invalid lines are skipped. */
object FurnitureStore {
    data class Decoded(val entries: List<StoredFurniture>, val invalid: Int)

    fun encode(list: List<StoredFurniture>): String =
        list.joinToString("\n") { "${it.type};${it.x};${it.y};${it.z};${it.yaw};${it.id}" }

    fun decode(text: String): Decoded {
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
        return Decoded(result, invalid)
    }
}
