package dev.kiln.client.screen

/** Integer rectangle with exclusive right/bottom edge (screen GUI pixels). */
data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
    val isEmpty: Boolean get() = x1 <= x0 || y1 <= y0

    fun contains(x: Double, y: Double): Boolean = x >= x0 && y >= y0 && x < x1 && y < y1

    fun intersect(other: Rect): Rect =
        Rect(maxOf(x0, other.x0), maxOf(y0, other.y0), minOf(x1, other.x1), minOf(y1, other.y1))
            .let { if (it.isEmpty) Rect(it.x0, it.y0, it.x0, it.y0) else it }

    companion object {
        fun of(x: Int, y: Int, width: Int, height: Int): Rect = Rect(x, y, x + width, y + height)
    }
}
