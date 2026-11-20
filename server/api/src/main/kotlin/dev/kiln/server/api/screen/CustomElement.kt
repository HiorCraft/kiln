package dev.kiln.server.api.screen

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component

/** Widget rendered by a client extension registered for [type]; clients without it draw a placeholder. */
public data class CustomElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val type: Key,
    val properties: Map<String, String>,
    val onClick: ((ClickContext) -> Unit)?,
    override val tooltip: Component? = null,
) : ScreenElement
