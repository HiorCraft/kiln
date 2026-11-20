package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl

/** Content of the screen root panel plus screen-level handlers. */
@KilnDsl
public class ScreenBuilder internal constructor(
    /** Root width; equals `viewport.width` for fullscreen screens. */
    public val width: Int,
    public val height: Int,
    /** The client's GUI-scaled window size ([Viewport.DEFAULT] until the client reported it). */
    public val viewport: Viewport = Viewport(width.coerceAtLeast(1), height.coerceAtLeast(1)),
) : ContainerBuilder(IdAllocator()) {
    private var closeHandler: ((CloseContext) -> Unit)? = null

    /** Called once when the screen closes, for whatever [CloseReason]. */
    public fun onClose(handler: (CloseContext) -> Unit) {
        closeHandler = handler
    }

    /**
     * A modal dialog above everything else, centered in the root unless [x]/[y] are given. Several modals stack;
     * the last one is on top and the only one receiving input. Remove it by re-rendering without it.
     */
    public fun modal(
        id: String,
        width: Int,
        height: Int,
        x: Int? = null,
        y: Int? = null,
        content: ModalBuilder.() -> Unit,
    ) {
        val modalId = ids.explicit(id)
        val builder = ModalBuilder(ids).apply(content)
        add(builder.build(modalId, x ?: (this.width - width) / 2, y ?: (this.height - height) / 2, width, height))
    }

    internal fun build(fullscreen: Boolean = false): RenderedScreen = RenderedScreen(
        PanelElement(ROOT_ID, 0, 0, width, height, children(), layout, background, texture, border, tooltip = tooltip),
        closeHandler,
        fullscreen,
    )

    public companion object {
        /** Id of the root panel. */
        public const val ROOT_ID: String = "root"
    }
}
