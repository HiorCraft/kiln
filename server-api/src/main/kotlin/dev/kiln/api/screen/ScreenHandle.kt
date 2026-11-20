package dev.kiln.api.screen

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

public enum class MouseButton { LEFT, RIGHT, MIDDLE, OTHER }

public enum class CloseReason {
    /** Escape / closed by the player. */
    PLAYER,

    /** [ScreenHandle.close] was called. */
    SERVER,

    /** Another screen was opened for the player. */
    REPLACED,

    /** The player left. */
    DISCONNECT,
}

/** Passed to click handlers of buttons, item slots, clickable panels and custom widgets. */
public class ClickContext(
    public val player: Player,
    public val screen: ScreenHandle,
    public val elementId: String,
    public val button: MouseButton,
    /** Shift was held (e.g. "sell the whole stack"). */
    public val shift: Boolean = false,
    public val control: Boolean = false,
    public val alt: Boolean = false,
)

/** Passed to inventory grid click handlers: the clicked [slot] (player inventory numbering) and its item at render time. */
public class InventorySlotClickContext(
    public val click: ClickContext,
    public val slot: Int,
    /** Snapshot from the last render; read the inventory again before moving items. */
    public val item: org.bukkit.inventory.ItemStack?,
) {
    public val player: Player get() = click.player
    public val screen: ScreenHandle get() = click.screen
    public val button: MouseButton get() = click.button
    public val shift: Boolean get() = click.shift
}

/**
 * Passed to input handlers (text field, dropdown, checkbox, slider). The value has already been validated against
 * the element the server last sent (length, numeric range, option exists, slider range/step).
 */
public class InputContext(
    public val player: Player,
    public val screen: ScreenHandle,
    public val elementId: String,
    public val value: String,
) {
    /** Checkbox state. */
    public val checked: Boolean get() = value == "true"

    /** Slider / numeric text field value; `null` for "" or non-numeric values. */
    public val number: Double? get() = value.toDoubleOrNull()

    /** [number] as whole number, if it is one. */
    public val int: Int? get() = number?.takeIf { it == Math.rint(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
}

/** Passed to a modal's `onDismiss` handler (Escape while the modal was the topmost one). */
public class ModalDismissContext(
    public val player: Player,
    public val screen: ScreenHandle,
    public val modalId: String,
)

/** Passed to `onClose`. */
public class CloseContext(
    public val player: Player,
    public val screen: ScreenHandle,
    public val reason: CloseReason,
)
