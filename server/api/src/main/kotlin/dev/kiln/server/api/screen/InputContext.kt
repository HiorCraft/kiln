package dev.kiln.server.api.screen

import org.bukkit.entity.Player

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
