package dev.kiln.server.api.screen

import org.bukkit.entity.Player

/**
 * An open screen of one player.
 *
 * Live updates: the screen's content lambda is re-evaluated by [refresh] (so it picks up current state captured
 * from your code) or replaced by [update]. Only the changed tree is sent; the client keeps focus, text and scroll
 * state of elements whose id is unchanged.
 */
public interface ScreenHandle {
    /** Session-unique id of this screen (also used on the wire). */
    public val id: Int
    public val player: Player
    public val definition: ScreenDefinition

    /** `false` after the screen was closed (by the player, the server, a replacement or a disconnect). */
    public val isOpen: Boolean

    /** The player's current GUI-scaled window size ([Viewport.DEFAULT] until the client reported it). */
    public val viewport: Viewport

    /** Re-evaluates the current content and sends the new tree. No-op when closed. */
    public fun refresh()

    /** Replaces the content (keeping width/height) and sends the new tree. No-op when closed. */
    public fun update(content: ScreenBuilder.() -> Unit)

    /**
     * Latest value the client reported for the input element [elementId] (text field content, dropdown option
     * value, `"true"`/`"false"` for checkboxes, slider number), or the value the server last sent; `null` for
     * unknown ids, non-input elements and dropdowns without selection.
     */
    public fun inputValue(elementId: String): String?

    /** Alias of [inputValue], kept for text fields. */
    public fun textValue(elementId: String): String? = inputValue(elementId)

    /** Closes the screen on the client; `onClose` runs with [CloseReason.SERVER]. */
    public fun close()
}
