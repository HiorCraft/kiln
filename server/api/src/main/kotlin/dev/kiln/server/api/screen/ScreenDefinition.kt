package dev.kiln.server.api.screen

/**
 * A reusable screen template: size plus a content lambda. The lambda is evaluated on [render] (when the screen is
 * opened, on every [ScreenHandle.refresh] and automatically when the client's [Viewport] changes), so it can simply
 * read your current state:
 *
 * ```
 * var progress = 0f
 * val factory = screen(176, 120) {
 *     layout = Layout.Column()
 *     text("Factory", width = 168, align = TextAlign.CENTER)
 *     progressBar(progress, width = 168)
 *     button("start", "Start") { onClick { ctx -> progress = 0.1f; ctx.screen.refresh() } }
 *     onClose { ctx -> ctx.player.sendMessage("closed: ${ctx.reason}") }
 * }
 * val shop = screen(fullscreen = true) {
 *     // width/height == viewport.width/height
 *     scrollPanel("offers", x = 10, y = 30, width = width - 20, height = height - 40) { ... }
 * }
 * val handle = player.openScreen(factory)
 * ```
 *
 * Ids: interactive elements (button, input, slot, scroll panel, modal, clickable panel, custom widget) take an
 * explicit id, which must be stable across updates because handlers and client state are bound to it. Passive
 * elements get an `auto:<n>` id unless you pass one (pass one if the element appears conditionally and you care
 * about client-side state).
 */
public class ScreenDefinition(
    /** Root size for fixed screens; ignored (0) for [fullscreen] screens. */
    public val width: Int,
    public val height: Int,
    /** The root always has the size of the client's [Viewport] and is placed at the window's top-left corner. */
    public val fullscreen: Boolean = false,
    public val content: ScreenBuilder.() -> Unit,
) {
    init {
        require(width in 0..ScreenLimits.MAX_SIZE && height in 0..ScreenLimits.MAX_SIZE) { "Screen size out of range: ${width}x$height" }
    }

    /** Evaluates the content for [viewport]. Throws [IllegalArgumentException] for invalid ids or values. */
    public fun render(viewport: Viewport = Viewport.DEFAULT): RenderedScreen {
        val rootWidth = if (fullscreen) viewport.width.coerceIn(1, ScreenLimits.MAX_SIZE) else width
        val rootHeight = if (fullscreen) viewport.height.coerceIn(1, ScreenLimits.MAX_SIZE) else height
        return ScreenBuilder(rootWidth, rootHeight, viewport).apply(content).build(fullscreen)
    }

    /** Same size, other content. */
    public fun withContent(content: ScreenBuilder.() -> Unit): ScreenDefinition = ScreenDefinition(width, height, fullscreen, content)
}

/** Defines a screen of [width] x [height] GUI pixels, centered on the client's window. */
public fun screen(width: Int, height: Int, content: ScreenBuilder.() -> Unit): ScreenDefinition =
    ScreenDefinition(width, height, false, content)

/**
 * Defines a fullscreen screen: the root is as large as the client's GUI-scaled window ([ScreenBuilder.viewport])
 * and is re-rendered automatically when that changes. `fullscreen` must be `true`; use `screen(width, height)` for
 * fixed-size screens.
 */
public fun screen(fullscreen: Boolean, content: ScreenBuilder.() -> Unit): ScreenDefinition {
    require(fullscreen) { "Use screen(width, height) { } for fixed-size screens" }
    return ScreenDefinition(0, 0, true, content)
}
