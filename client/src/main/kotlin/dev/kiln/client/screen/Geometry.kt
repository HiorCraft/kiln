package dev.kiln.client.screen

/** Screen geometry of one placed element for the current frame: scrolled position and the clip of its scroll parents. */
data class Geometry(val rect: Rect, val clip: Rect?) {
    /** `true` if ([x], [y]) is inside the element and inside its visible (clipped) area. */
    fun hit(x: Double, y: Double): Boolean = rect.contains(x, y) && (clip == null || clip.contains(x, y))
}
