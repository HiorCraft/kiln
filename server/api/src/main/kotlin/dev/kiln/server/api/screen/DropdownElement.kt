package dev.kiln.server.api.screen

import net.kyori.adventure.text.Component

public data class DropdownElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val options: List<DropdownOption>,
    /** Value of the selected option or `null`; like a text field value, the client keeps a local choice until this changes. */
    val selected: String?,
    val placeholder: Component?,
    val enabled: Boolean,
    override val tooltip: Component?,
    val onChange: ((InputContext) -> Unit)?,
) : ScreenElement
