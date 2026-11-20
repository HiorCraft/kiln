package dev.kiln.server.api.screen

import org.bukkit.entity.Player

/** Passed to a modal's `onDismiss` handler (Escape while the modal was the topmost one). */
public class ModalDismissContext(
    public val player: Player,
    public val screen: ScreenHandle,
    public val modalId: String,
)
