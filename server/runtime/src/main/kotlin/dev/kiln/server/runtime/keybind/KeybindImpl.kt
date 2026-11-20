package dev.kiln.server.runtime.keybind

import dev.kiln.server.api.keybind.KeyCode
import dev.kiln.server.api.keybind.Keybind
import dev.kiln.server.api.keybind.KeybindHandlers
import dev.kiln.protocol.message.KeybindDeclaration
import net.kyori.adventure.key.Key
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** A registered server keybind; its wire id is `key.asString()`. */
class KeybindImpl(
    private val registry: KeybindRegistry,
    override val plugin: Plugin,
    override val key: Key,
    override val name: String,
    override val category: String,
    override val defaultKey: KeyCode,
    val handlers: KeybindHandlers,
) : Keybind {
    val wireId: String = key.asString()

    override fun isPressed(player: Player): Boolean = registry.pressedLookup(player, wireId)

    override fun unregister() {
        registry.remove(this)
    }

    fun declaration(): KeybindDeclaration = KeybindDeclaration(wireId, name, category, defaultKey.name)

    override fun toString(): String = "Keybind($wireId)"
}
