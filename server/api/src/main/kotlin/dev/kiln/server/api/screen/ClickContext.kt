package dev.kiln.server.api.screen

import org.bukkit.entity.Player

/** Passed to click handlers of buttons, item slots, clickable panels and custom widgets. */
public class ClickContext(
    public val player: Player,
    public val screen: ScreenHandle,
    public val elementId: String,
    public val button: MouseButton,
    /** Shift was held (e.g. "sell the whole stack"). */
    public val shift: Boolean = false,
    public val control: Boolean = false,
    public val alt: Boolean = false,
)
