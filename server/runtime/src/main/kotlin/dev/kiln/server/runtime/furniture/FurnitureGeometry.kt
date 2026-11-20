package dev.kiln.server.runtime.furniture

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin

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

    /** Rider and seat positions this close (blocks, per axis) are the same seat. */
    private const val SEAT_TOLERANCE = 0.05

    /** Indices of [seats] a rider sits on (rider seat positions within [SEAT_TOLERANCE]). */
    fun occupiedSeats(seats: List<Vec>, riders: List<Vec>): Set<Int> =
        seats.indices.filter { i ->
            riders.any { r -> abs(r.x - seats[i].x) < SEAT_TOLERANCE && abs(r.y - seats[i].y) < SEAT_TOLERANCE && abs(r.z - seats[i].z) < SEAT_TOLERANCE }
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
