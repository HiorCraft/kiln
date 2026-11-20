package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.Color
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.codec.DepthCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

/**
 * Dialog drawn above everything else (except an open [Dropdown] list). Only allowed as a **direct child of the
 * root**. While at least one modal is present, the client dims the whole window with [dimColor] below the topmost
 * modal (the last [Modal] among the root's children), blocks input to everything outside it and sends
 * [ModalDismiss] (instead of closing the screen) when the player presses Escape. The server decides what happens
 * (usually: re-render without the modal).
 */
public data class Modal(
    override val id: String,
    override val bounds: Bounds,
    override val children: List<Element> = emptyList(),
    val background: Color? = Color(0xF0101010.toInt()),
    val border: Color? = Color(0xFF808080.toInt()),
    val dimColor: Color = Color(0xA0000000.toInt()),
    override val tooltip: String? = null,
) : Container {
    /** Wire codec: the element header, then the fields below; only valid as a direct child of the root. */
    public companion object : DepthCodec<Modal> {
        /** Depth of the root's direct children. */
        private const val MODAL_DEPTH = 2

        private val color = Color.nullable()

        override fun encode(writer: ProtocolWriter, value: Modal, depth: Int) {
            checkPlacement(value.id, depth)
            ElementHeader.encode(writer, ElementHeader.of(value))
            color.encode(writer, value.background)
            color.encode(writer, value.border)
            Color.encode(writer, value.dimColor)
            childrenCodec(depth).encode(writer, value.children)
        }

        override fun decode(reader: ProtocolReader, depth: Int): Modal {
            val header = ElementHeader.decode(reader)
            checkPlacement(header.id, depth)
            return Modal(
                id = header.id,
                bounds = header.bounds,
                background = color.decode(reader),
                border = color.decode(reader),
                dimColor = Color.decode(reader),
                children = childrenCodec(depth).decode(reader),
                tooltip = header.tooltip,
            )
        }

        private fun checkPlacement(id: String, depth: Int) {
            if (depth != MODAL_DEPTH) throw ProtocolException("Modal '$id' must be a direct child of the root")
        }
    }
}
