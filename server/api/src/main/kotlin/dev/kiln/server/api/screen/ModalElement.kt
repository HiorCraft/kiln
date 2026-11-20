package dev.kiln.server.api.screen

import net.kyori.adventure.text.Component
import org.bukkit.Color

/** Dialog above everything else; only allowed directly in the screen root. */
public data class ModalElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    override val children: List<ScreenElement>,
    override val layout: Layout,
    val background: Color?,
    val border: Color?,
    val dimColor: Color,
    val onDismiss: ((ModalDismissContext) -> Unit)?,
    override val tooltip: Component? = null,
) : ContainerElement
