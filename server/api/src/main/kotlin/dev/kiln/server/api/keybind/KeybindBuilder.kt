package dev.kiln.server.api.keybind

import dev.kiln.server.api.KilnDsl
import org.bukkit.entity.Player

/** Configures the handlers of a keybind. Handlers run on the main thread. */
@KilnDsl
public class KeybindBuilder {
    private var pressHandler: ((Player) -> Unit)? = null

    private var releaseHandler: ((Player) -> Unit)? = null

    public fun onPress(handler: (player: Player) -> Unit) {
        pressHandler = handler
    }

    public fun onRelease(handler: (player: Player) -> Unit) {
        releaseHandler = handler
    }

    /** Result read by the implementation. */
    public fun build(): KeybindHandlers = KeybindHandlers(pressHandler, releaseHandler)
}
