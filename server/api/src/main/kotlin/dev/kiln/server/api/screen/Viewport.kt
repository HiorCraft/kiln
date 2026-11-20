package dev.kiln.server.api.screen

/**
 * The client's GUI-scaled window size in GUI pixels (what `width`/`height` of a fullscreen root are).
 * Before the client reported it, [DEFAULT] is used.
 */
public data class Viewport(val width: Int, val height: Int) {
    public companion object {
        /** 1920x1080 at GUI scale 4. */
        public val DEFAULT: Viewport = Viewport(480, 270)
    }
}
