package dev.kiln.server.api.screen

import org.bukkit.entity.Player

/** Passed to `onClose`. */
public class CloseContext(
    public val player: Player,
    public val screen: ScreenHandle,
    public val reason: CloseReason,
)
