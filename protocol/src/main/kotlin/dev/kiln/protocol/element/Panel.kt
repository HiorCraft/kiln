package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.Color
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.codec.DepthCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

/**
 * Plain container. With [clickable] = `true` a click anywhere on the panel that is not consumed by an interactive
 * child sends [ScreenClick] (tiles, tabs). Background precedence: [selected] -> [selectedBackground] (falling back
 * to [background]), otherwise hovered -> [hoverBackground] (falling back to [background]).
 */
public data class Panel(
    override val id: String,
    override val bounds: Bounds,
    override val children: List<Element> = emptyList(),
    val background: Color? = null,
    val texture: ResourceId? = null,
    val border: Color? = null,
    val hoverBackground: Color? = null,
    val selectedBackground: Color? = null,
    val selected: Boolean = false,
    val clickable: Boolean = false,
    override val tooltip: String? = null,
) : Container {
    /** Wire codec: the element header, then the fields below. */
    public companion object : DepthCodec<Panel> {
        private val color = Color.nullable()
        private val texture = ResourceId.codec("panel texture").nullable()

        override fun encode(writer: ProtocolWriter, value: Panel, depth: Int) {
            ElementHeader.encode(writer, ElementHeader.of(value))
            color.encode(writer, value.background)
            texture.encode(writer, value.texture)
            color.encode(writer, value.border)
            color.encode(writer, value.hoverBackground)
            color.encode(writer, value.selectedBackground)
            writer.boolean(value.selected)
            writer.boolean(value.clickable)
            childrenCodec(depth).encode(writer, value.children)
        }

        override fun decode(reader: ProtocolReader, depth: Int): Panel {
            val header = ElementHeader.decode(reader)
            return Panel(
                id = header.id,
                bounds = header.bounds,
                background = color.decode(reader),
                texture = texture.decode(reader),
                border = color.decode(reader),
                hoverBackground = color.decode(reader),
                selectedBackground = color.decode(reader),
                selected = reader.boolean(),
                clickable = reader.boolean(),
                children = childrenCodec(depth).decode(reader),
                tooltip = header.tooltip,
            )
        }
    }
}
