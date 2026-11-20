package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.Color
import dev.kiln.protocol.ProtocolLimits.MAX_SCROLL_CONTENT_HEIGHT
import dev.kiln.protocol.codec.DepthCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec

/**
 * Vertically scrollable container. Children are laid out in a content area of `bounds.width` x [contentHeight]
 * (their bounds are relative to its top-left corner); the client clips them to [bounds] (scissor), scrolls with the
 * mouse wheel / a scrollbar and keeps the scroll offset per element id across updates (clamped when the content
 * shrinks). [contentHeight] smaller than `bounds.height` simply means nothing to scroll.
 */
public data class ScrollPanel(
    override val id: String,
    override val bounds: Bounds,
    override val children: List<Element> = emptyList(),
    val contentHeight: Int,
    val background: Color? = null,
    val border: Color? = null,
    /** Draw a scrollbar at the right edge (inside [bounds]) when the content is taller than the panel. */
    val scrollbar: Boolean = true,
    override val tooltip: String? = null,
) : Container {
    /** Wire codec: the element header, then the fields below. */
    public companion object : DepthCodec<ScrollPanel> {
        private val contentHeight = RangedVarIntCodec(0, MAX_SCROLL_CONTENT_HEIGHT, "content height")
        private val color = Color.nullable()

        override fun encode(writer: ProtocolWriter, value: ScrollPanel, depth: Int) {
            ElementHeader.encode(writer, ElementHeader.of(value))
            contentHeight.encode(writer, value.contentHeight)
            color.encode(writer, value.background)
            color.encode(writer, value.border)
            writer.boolean(value.scrollbar)
            childrenCodec(depth).encode(writer, value.children)
        }

        override fun decode(reader: ProtocolReader, depth: Int): ScrollPanel {
            val header = ElementHeader.decode(reader)
            return ScrollPanel(
                id = header.id,
                bounds = header.bounds,
                contentHeight = contentHeight.decode(reader),
                background = color.decode(reader),
                border = color.decode(reader),
                scrollbar = reader.boolean(),
                children = childrenCodec(depth).decode(reader),
                tooltip = header.tooltip,
            )
        }
    }
}
