package dev.kiln.client.lua.graphics

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/** Rasterisation of the gauge arc `g:arc`. */
object GaugeArc {
    /**
     * Pixel runs of a gauge arc: the **upper half** of a ring around ([cx], [cy]) (outer [radius], [thickness]),
     * from fraction [from] to [to], where 0 is the left end, 0.5 the top and 1 the right end. Emits vertical runs
     * `(x, y0, y1)` (y1 exclusive); nothing is drawn below [cy].
     */
    fun runs(cx: Double, cy: Double, radius: Double, thickness: Double, from: Double, to: Double, emit: (Int, Int, Int) -> Unit) {
        val lo = from.coerceIn(0.0, 1.0)
        val hi = to.coerceIn(0.0, 1.0)
        if (radius <= 0 || thickness <= 0 || hi <= lo) return
        val outer = radius
        val inner = (radius - thickness).coerceAtLeast(0.0)

        fun inside(px: Int, py: Int): Boolean {
            val dx = px + 0.5 - cx
            val dy = cy - (py + 0.5) // > 0 above the center
            if (dy < 0 || sqrt(dx * dx + dy * dy) !in inner..outer) return false
            val t = atan2(dy, -dx) / Math.PI // 0 left, 0.5 top, 1 right
            return t >= lo && t <= hi
        }

        val y0 = floor(cy - outer).toInt()
        val yEnd = floor(cy).toInt() // exclusive
        for (px in floor(cx - outer).toInt()..floor(cx + outer).toInt()) {
            var runStart = NO_RUN
            for (py in y0..yEnd) {
                val covered = py < yEnd && inside(px, py)
                if (covered && runStart == NO_RUN) runStart = py
                if (!covered && runStart != NO_RUN) {
                    emit(px, runStart, py)
                    runStart = NO_RUN
                }
            }
        }
    }

    private const val NO_RUN = Int.MIN_VALUE
}
