package dev.kiln.server.api.keybind

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
