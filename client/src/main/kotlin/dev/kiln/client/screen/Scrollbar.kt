package dev.kiln.client.screen

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/** The vanilla scrollbar (track and thumb sprites) of the runtime's scrollable areas. */
internal object Scrollbar {
    private val THUMB: Identifier = Identifier.withDefaultNamespace("widget/scroller")
    private val TRACK: Identifier = Identifier.withDefaultNamespace("widget/scroller_background")

    /** Draws the scrollbar [bar] of a viewport as high as [bar], scrolled by [offset] over [contentHeight] pixels. */
    fun extract(graphics: GuiGraphicsExtractor, bar: Rect, offset: Double, contentHeight: Int) {
        val thumbHeight = ScrollMath.thumbHeight(contentHeight, bar.height)
        val thumbY = bar.y0 + ScrollMath.thumbY(offset, contentHeight, bar.height)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, TRACK, bar.x0, bar.y0, bar.width, bar.height)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, THUMB, bar.x0, thumbY, bar.width, thumbHeight)
    }
}
