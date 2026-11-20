package dev.kiln.server.api.screen

import net.kyori.adventure.text.Component

public data class SliderElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val min: Double,
    val max: Double,
    /** Snap step, `0` = continuous. */
    val step: Double,
    val value: Double,
    val label: Component?,
    val enabled: Boolean,
    override val tooltip: Component?,
    val onChange: ((InputContext) -> Unit)?,
) : ScreenElement
