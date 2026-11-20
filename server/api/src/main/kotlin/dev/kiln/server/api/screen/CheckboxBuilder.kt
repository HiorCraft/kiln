package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import net.kyori.adventure.text.Component

@KilnDsl
public class CheckboxBuilder internal constructor() {
    public var checked: Boolean = false
    public var label: Component? = null
    public var style: CheckboxStyle = CheckboxStyle.CHECKBOX
    public var enabled: Boolean = true
    public var tooltip: Component? = null
    internal var changeHandler: ((InputContext) -> Unit)? = null

    /** Called with the new state ([InputContext.checked]). */
    public fun onChange(handler: (InputContext) -> Unit) {
        changeHandler = handler
    }
}
