package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import net.kyori.adventure.text.Component

@KilnDsl
public class DropdownBuilder internal constructor() {
    internal val options = mutableListOf<DropdownOption>()

    /** Value of the selected option, `null` = none (shows [placeholder]). */
    public var selected: String? = null
    public var placeholder: Component? = null
    public var enabled: Boolean = true
    public var tooltip: Component? = null
    internal var changeHandler: ((InputContext) -> Unit)? = null

    public fun option(value: String, label: Component) {
        options += DropdownOption(value, label)
    }

    public fun option(value: String, label: String): Unit = option(value, Component.text(label))

    /** Called with the chosen option value ([InputContext.value]). */
    public fun onChange(handler: (InputContext) -> Unit) {
        changeHandler = handler
    }
}
