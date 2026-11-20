package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

/** Renders an item (scaled to [bounds]) with the vanilla tooltip on hover (unless [showItemTooltip] is `false`). */
public data class ItemDisplay(
    override val id: String,
    override val bounds: Bounds,
    val item: ItemStackData,
    val showItemTooltip: Boolean = true,
    override val tooltip: String? = null,
) : Element {
    /** Wire codec: the element header, then the fields below. */
    public companion object : Codec<ItemDisplay> {
        override fun encode(writer: ProtocolWriter, value: ItemDisplay) {
            ElementHeader.encode(writer, ElementHeader.of(value))
            ItemStackData.encode(writer, value.item)
            writer.boolean(value.showItemTooltip)
        }

        override fun decode(reader: ProtocolReader): ItemDisplay {
            val header = ElementHeader.decode(reader)
            return ItemDisplay(
                id = header.id,
                bounds = header.bounds,
                item = ItemStackData.decode(reader),
                showItemTooltip = reader.boolean(),
                tooltip = header.tooltip,
            )
        }
    }
}
