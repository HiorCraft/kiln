package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

/**
 * Inventory-like slot: optional item, hover highlight, optional selection frame. Clicking (any button, with
 * modifiers) sends [ScreenClick]; the server performs all item movements itself, the client never moves items.
 * An empty slot shows [placeholderSprite] (a GUI sprite id, e.g. `minecraft:container/slot/sword`), if set.
 */
public data class ItemSlot(
    override val id: String,
    override val bounds: Bounds,
    val item: ItemStackData? = null,
    val placeholderSprite: ResourceId? = null,
    /** Draw the vanilla slot background (`minecraft:container/slot`) behind the item. */
    val slotBackground: Boolean = true,
    /** Draw the vanilla white hover highlight. */
    val highlight: Boolean = true,
    val selected: Boolean = false,
    /** Disabled slots are drawn greyed out and send no clicks. */
    val enabled: Boolean = true,
    val showItemTooltip: Boolean = true,
    override val tooltip: String? = null,
) : Element {
    /** Wire codec: the element header, then the fields below. */
    public companion object : Codec<ItemSlot> {
        private val item = ItemStackData.nullable()
        private val placeholderSprite = ResourceId.codec("placeholder sprite").nullable()

        override fun encode(writer: ProtocolWriter, value: ItemSlot) {
            ElementHeader.encode(writer, ElementHeader.of(value))
            item.encode(writer, value.item)
            placeholderSprite.encode(writer, value.placeholderSprite)
            writer.boolean(value.slotBackground)
            writer.boolean(value.highlight)
            writer.boolean(value.selected)
            writer.boolean(value.enabled)
            writer.boolean(value.showItemTooltip)
        }

        override fun decode(reader: ProtocolReader): ItemSlot {
            val header = ElementHeader.decode(reader)
            return ItemSlot(
                id = header.id,
                bounds = header.bounds,
                item = item.decode(reader),
                placeholderSprite = placeholderSprite.decode(reader),
                slotBackground = reader.boolean(),
                highlight = reader.boolean(),
                selected = reader.boolean(),
                enabled = reader.boolean(),
                showItemTooltip = reader.boolean(),
                tooltip = header.tooltip,
            )
        }
    }
}
