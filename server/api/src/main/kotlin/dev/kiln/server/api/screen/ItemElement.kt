package dev.kiln.server.api.screen

import net.kyori.adventure.text.Component

public data class ItemElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val item: ScreenItem,
    val showItemTooltip: Boolean = true,
    override val tooltip: Component? = null,
) : ScreenElement
