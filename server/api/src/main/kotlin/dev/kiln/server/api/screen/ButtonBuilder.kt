package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import net.kyori.adventure.text.Component

@KilnDsl
public class ButtonBuilder internal constructor() {
    public var enabled: Boolean = true
    public var tooltip: Component? = null
    internal var clickHandler: ((ClickContext) -> Unit)? = null

    public fun onClick(handler: (ClickContext) -> Unit) {
        clickHandler = handler
    }
}
