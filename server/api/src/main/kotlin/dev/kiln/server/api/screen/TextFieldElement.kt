package dev.kiln.server.api.screen

import net.kyori.adventure.text.Component

public data class TextFieldElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    /** Initial value; the client keeps the player's edits across updates as long as this value is unchanged. */
    val value: String,
    val placeholder: Component?,
    val maxLength: Int,
    val onInput: ((InputContext) -> Unit)?,
    val numeric: NumericRange? = null,
    override val tooltip: Component? = null,
) : ScreenElement
