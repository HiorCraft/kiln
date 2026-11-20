package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import net.kyori.adventure.text.Component

@KilnDsl
public class SliderBuilder internal constructor() {
    public var min: Double = 0.0
    public var max: Double = 1.0

    /** Snap step, `0` = continuous. */
    public var step: Double = 0.0
    public var value: Double = 0.0

    /** Drawn as `label: value`. */
    public var label: Component? = null
    public var enabled: Boolean = true
    public var tooltip: Component? = null
    internal var changeHandler: ((InputContext) -> Unit)? = null

    /** Called with the new value ([InputContext.number]) while dragging (throttled) and on release. */
    public fun onChange(handler: (InputContext) -> Unit) {
        changeHandler = handler
    }
}
