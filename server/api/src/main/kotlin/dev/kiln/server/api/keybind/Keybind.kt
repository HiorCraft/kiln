package dev.kiln.server.api.keybind

import net.kyori.adventure.key.Key
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

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
