package dev.kiln.server.api.screen

import net.kyori.adventure.text.Component
import org.bukkit.Color

public data class TextElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val text: Component,
    val color: Color,
    val align: TextAlign,
    val shadow: Boolean,
    override val tooltip: Component? = null,
) : ScreenElement
