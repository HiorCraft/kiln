package dev.kiln.server.api.screen

import net.kyori.adventure.text.Component

public data class CheckboxElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val checked: Boolean,
    val label: Component?,
    val style: CheckboxStyle,
    val enabled: Boolean,
    override val tooltip: Component?,
    val onChange: ((InputContext) -> Unit)?,
) : ScreenElement
