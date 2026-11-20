package dev.kiln.server.api.screen

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Color

public data class PanelElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    override val children: List<ScreenElement>,
    override val layout: Layout,
    val background: Color?,
    val texture: Key?,
    val border: Color?,
    val hoverBackground: Color? = null,
    val selectedBackground: Color? = null,
    val selected: Boolean = false,
    override val tooltip: Component? = null,
    /** Non-null makes the panel clickable (tiles, tabs). */
    val onClick: ((ClickContext) -> Unit)? = null,
) : ContainerElement
