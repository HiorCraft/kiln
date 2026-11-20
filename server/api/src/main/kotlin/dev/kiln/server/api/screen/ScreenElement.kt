package dev.kiln.server.api.screen

import net.kyori.adventure.text.Component

/**
 * Rendered, immutable GUI tree produced by the screen DSL. The implementation converts it to the wire format and
 * keeps the handlers server-side; clients only ever see data.
 *
 * Coordinates are GUI pixels relative to the parent element (inside a [Layout.Absolute] panel; inside a
 * [ScrollPanelElement] relative to the top of its scrollable content). The root panel is centered on the client's
 * screen, or fills the window for fullscreen screens.
 */
public sealed interface ScreenElement {
    /** Stable id; explicit ids keep client state (focus, text, scroll) across updates, auto ids start with `auto:`. */
    public val id: String
    public val x: Int
    public val y: Int
    public val width: Int
    public val height: Int

    /** Shown while hovering the element; for items appended below the vanilla item tooltip. */
    public val tooltip: Component?
}
