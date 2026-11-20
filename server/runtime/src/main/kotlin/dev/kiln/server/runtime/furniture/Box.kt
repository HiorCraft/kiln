package dev.kiln.server.runtime.furniture

import kotlin.math.round

/** Axis-aligned box in world coordinates. */
data class Box(val minX: Double, val minY: Double, val minZ: Double, val maxX: Double, val maxY: Double, val maxZ: Double) {
    /** Rounded to 1e-6 (comparisons in tests and logs). */
    fun rounded(): Box {
        fun r(v: Double) = round(v * 1e6) / 1e6
        return Box(r(minX), r(minY), r(minZ), r(maxX), r(maxY), r(maxZ))
    }
}
