package dev.kiln.server.api.keybind

import org.bukkit.entity.Player

/** The handlers configured in a [KeybindBuilder]. */
public class KeybindHandlers(
    public val onPress: ((Player) -> Unit)?,
    public val onRelease: ((Player) -> Unit)?,
)
