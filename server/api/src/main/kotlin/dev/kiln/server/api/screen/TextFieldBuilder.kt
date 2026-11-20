package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import net.kyori.adventure.text.Component

@KilnDsl
public class TextFieldBuilder internal constructor() {
    public var value: String = ""
    public var placeholder: Component? = null
    public var maxLength: Int = 256
    public var tooltip: Component? = null
    internal var numeric: NumericRange? = null
    internal var inputHandler: ((InputContext) -> Unit)? = null

    /** Restricts input to numbers in [min]..[max] (whole numbers if [integer]); reported values are valid or "". */
    public fun numeric(min: Double, max: Double, integer: Boolean = true) {
        numeric = NumericRange(min, max, integer)
    }

    /** Called with the new content (throttled while typing, immediately on enter). */
    public fun onInput(handler: (InputContext) -> Unit) {
        inputHandler = handler
    }
}
