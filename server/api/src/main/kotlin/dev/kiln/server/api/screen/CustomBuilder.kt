package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import net.kyori.adventure.text.Component

@KilnDsl
public class CustomBuilder internal constructor() {
    /** Free-form string properties passed to the client widget renderer. */
    public val properties: MutableMap<String, String> = LinkedHashMap()
    public var tooltip: Component? = null
    internal var clickHandler: ((ClickContext) -> Unit)? = null

    public fun property(key: String, value: Any) {
        properties[key] = value.toString()
    }

    public fun onClick(handler: (ClickContext) -> Unit) {
        clickHandler = handler
    }
}
