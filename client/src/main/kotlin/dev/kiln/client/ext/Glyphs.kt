package dev.kiln.client.ext

import net.minecraft.client.gui.GuiGraphicsExtractor

/** Small pixel-art glyphs for the extension screens. */
object Glyphs {
    const val GREEN = 0xFF55E05A.toInt()
    const val RED = 0xFFFF5A55.toInt()

    /** Chevron (7 px tall) centered at ([cx], [cy]); [turn] 0 = pointing right, 1 = pointing down (rotated 90°). */
    fun chevron(graphics: GuiGraphicsExtractor, cx: Float, cy: Float, turn: Float, color: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(cx, cy)
        pose.rotate((Math.PI / 2 * turn).toFloat())
        for (col in 0 until 4) graphics.fill(-2 + col, -3 + col, -1 + col, 4 - col, color)
        pose.popMatrix()
    }

    /** Green check mark in an 8x8 box. */
    fun check(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        val ys = intArrayOf(4, 5, 6, 5, 4, 3, 2, 1)
        for (i in ys.indices) graphics.fill(x + i, y + ys[i], x + i + 1, y + ys[i] + 2, GREEN)
    }

    /** Red X in an 8x8 box. */
    fun cross(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        for (i in 0 until 7) {
            graphics.fill(x + i, y + i, x + i + 1, y + i + 2, RED)
            graphics.fill(x + 6 - i, y + i, x + 7 - i, y + i + 2, RED)
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.GERMANY, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.GERMANY, "%.2f MB", bytes / 1024.0 / 1024.0)
    }
}
