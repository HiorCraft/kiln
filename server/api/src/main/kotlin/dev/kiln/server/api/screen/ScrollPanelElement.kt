package dev.kiln.server.api.screen

import net.kyori.adventure.text.Component
import org.bukkit.Color

/** Vertically scrolling container; [contentHeight] `null` = computed from the children (bottom edge + padding). */
public data class ScrollPanelElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    override val children: List<ScreenElement>,
    override val layout: Layout,
    val contentHeight: Int?,
    val background: Color?,
    val border: Color?,
    val scrollbar: Boolean,
    override val tooltip: Component? = null,
) : ContainerElement
