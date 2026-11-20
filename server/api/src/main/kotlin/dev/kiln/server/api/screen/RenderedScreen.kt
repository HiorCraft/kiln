package dev.kiln.server.api.screen

/** Result of evaluating a [ScreenDefinition]: the root panel plus screen-level handlers. */
public class RenderedScreen(
    public val root: PanelElement,
    public val onClose: ((CloseContext) -> Unit)?,
    /** The root fills the client window ([ScreenDefinition.fullscreen]). */
    public val fullscreen: Boolean = false,
)
