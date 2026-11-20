package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl

/** Content of a scroll panel ([texture] is ignored). */
@KilnDsl
public class ScrollPanelBuilder internal constructor(ids: IdAllocator) : ContainerBuilder(ids) {
    public var scrollbar: Boolean = true

    internal fun build(id: String, x: Int, y: Int, width: Int, height: Int, contentHeight: Int?): ScrollPanelElement =
        ScrollPanelElement(id, x, y, width, height, children(), layout, contentHeight, background, border, scrollbar, tooltip)
}
