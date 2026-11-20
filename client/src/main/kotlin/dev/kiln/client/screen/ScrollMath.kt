package dev.kiln.client.screen

import kotlin.math.roundToInt

/** Scroll offset and scrollbar geometry of a vertically scrolling viewport (pure math, GUI pixels). */
object ScrollMath {
    /** Width of the vanilla scrollbar (`widget/scroller`). */
    const val SCROLLBAR_WIDTH: Int = 6

    /** Minimum height of the scrollbar thumb (vanilla uses 32, smaller viewports shrink it). */
    const val MIN_THUMB: Int = 32

    /** Pixels scrolled per mouse wheel notch. */
    const val WHEEL_STEP: Double = 20.0

    fun maxScroll(contentHeight: Int, viewportHeight: Int): Int = (contentHeight - viewportHeight).coerceAtLeast(0)

    fun canScroll(contentHeight: Int, viewportHeight: Int): Boolean = maxScroll(contentHeight, viewportHeight) > 0

    fun clamp(offset: Double, contentHeight: Int, viewportHeight: Int): Double {
        if (!offset.isFinite()) return 0.0
        return offset.coerceIn(0.0, maxScroll(contentHeight, viewportHeight).toDouble())
    }

    /** Height of the scrollbar thumb, proportional to the visible fraction. */
    fun thumbHeight(contentHeight: Int, viewportHeight: Int): Int {
        if (viewportHeight <= 0) return 0
        if (contentHeight <= viewportHeight) return viewportHeight
        val proportional = (viewportHeight.toLong() * viewportHeight / contentHeight).toInt()
        val min = minOf(MIN_THUMB, viewportHeight / 2).coerceAtLeast(4)
        return proportional.coerceIn(min, viewportHeight)
    }

    /** Thumb top relative to the viewport top. */
    fun thumbY(offset: Double, contentHeight: Int, viewportHeight: Int): Int {
        val max = maxScroll(contentHeight, viewportHeight)
        if (max == 0) return 0
        val track = viewportHeight - thumbHeight(contentHeight, viewportHeight)
        return (clamp(offset, contentHeight, viewportHeight) * track / max).roundToInt().coerceIn(0, track)
    }

    /** Scroll offset after dragging the thumb by [dy] pixels, starting from [startOffset]. */
    fun dragOffset(startOffset: Double, dy: Double, contentHeight: Int, viewportHeight: Int): Double {
        val max = maxScroll(contentHeight, viewportHeight)
        val track = viewportHeight - thumbHeight(contentHeight, viewportHeight)
        if (max == 0 || track <= 0) return 0.0
        return clamp(startOffset + dy * max / track, contentHeight, viewportHeight)
    }

    /** Offset that centers the thumb at [y] (relative to the viewport top) – a click into the scrollbar track. */
    fun offsetForTrackClick(y: Double, contentHeight: Int, viewportHeight: Int): Double {
        val thumb = thumbHeight(contentHeight, viewportHeight)
        return dragOffset(0.0, y - thumb / 2.0, contentHeight, viewportHeight)
    }

    /**
     * Smallest change of [offset] that makes the range [top]..[bottom] (content coordinates) visible in the viewport
     * (`top` wins if the range is taller than the viewport).
     */
    fun ensureVisible(offset: Double, top: Int, bottom: Int, contentHeight: Int, viewportHeight: Int): Double {
        var result = offset
        if (bottom > result + viewportHeight) result = (bottom - viewportHeight).toDouble()
        if (top < result) result = top.toDouble()
        return clamp(result, contentHeight, viewportHeight)
    }
}
