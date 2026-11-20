package dev.kiln.api.keybind

import dev.kiln.api.KilnDsl
import net.kyori.adventure.key.Key
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * A default key binding, as a Minecraft `InputConstants` key name (`key.keyboard.g`, `key.mouse.middle`, ...).
 * The player can rebind it on the client.
 */
@JvmInline
public value class KeyCode(public val name: String) {
    override fun toString(): String = name

    public companion object {
        /** No default binding. */
        public val UNBOUND: KeyCode = KeyCode("key.keyboard.unknown")

        /** Keyboard key by its Minecraft name, e.g. `g`, `f6`, `left.shift`, `keypad.1`. */
        @JvmStatic
        public fun keyboard(name: String): KeyCode = KeyCode("key.keyboard.$name")

        /** Mouse button: `left`, `right`, `middle`, `4`, `5`, ... */
        @JvmStatic
        public fun mouse(name: String): KeyCode = KeyCode("key.mouse.$name")
    }
}

/** A declared server keybind. */
public interface Keybind {
    public val plugin: Plugin
    public val key: Key
    public val name: String
    public val category: String
    public val defaultKey: KeyCode

    /** Whether [player] currently holds the key (last press/release reported by the client). */
    public fun isPressed(player: Player): Boolean

    /** Removes the keybind from all clients. */
    public fun unregister()
}

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

/** The handlers configured in a [KeybindBuilder]. */
public class KeybindHandlers(
    public val onPress: ((Player) -> Unit)?,
    public val onRelease: ((Player) -> Unit)?,
)
