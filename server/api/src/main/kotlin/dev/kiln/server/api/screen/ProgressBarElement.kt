package dev.kiln.server.api.screen

import net.kyori.adventure.text.Component
import org.bukkit.Color

public data class ProgressBarElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    /** 0.0 .. 1.0 */
    val value: Float,
    val color: Color,
    val background: Color,
    override val tooltip: Component? = null,
) : ScreenElement
