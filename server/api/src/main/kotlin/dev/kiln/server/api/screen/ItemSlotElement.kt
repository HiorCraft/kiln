package dev.kiln.server.api.screen

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component

/** Clickable inventory-like slot. Item movements are entirely up to the click handler (server-side). */
public data class ItemSlotElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val item: ScreenItem?,
    val placeholderSprite: Key?,
    val slotBackground: Boolean,
    val highlight: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val showItemTooltip: Boolean,
    override val tooltip: Component?,
    val onClick: ((ClickContext) -> Unit)?,
) : ScreenElement
