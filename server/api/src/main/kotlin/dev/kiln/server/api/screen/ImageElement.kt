package dev.kiln.server.api.screen

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component

/** Region [u]/[v] ([regionWidth] x [regionHeight]) of a [textureWidth] x [textureHeight] texture, scaled to the bounds. */
public data class ImageElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val texture: Key,
    val u: Float,
    val v: Float,
    val regionWidth: Int,
    val regionHeight: Int,
    val textureWidth: Int,
    val textureHeight: Int,
    override val tooltip: Component? = null,
) : ScreenElement
