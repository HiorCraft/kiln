package dev.kiln.example.server.shop

/** Grid math for the offer tiles (pure). */
object ShopLayout {
    /** Number of tile columns for an area of [width] (at least 1). */
    fun columns(width: Int, minTileWidth: Int, gap: Int): Int = maxOf(1, (width + gap) / (minTileWidth + gap))

    /** Width of each of [columns] tiles filling [width]. */
    fun tileWidth(width: Int, columns: Int, gap: Int): Int = maxOf(1, (width - gap * (columns - 1)) / columns)
}
