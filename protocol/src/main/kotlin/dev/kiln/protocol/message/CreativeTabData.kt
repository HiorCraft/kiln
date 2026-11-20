package dev.kiln.protocol.message

import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.ProtocolLimits.MAX_CREATIVE_ITEMS
import dev.kiln.protocol.ProtocolLimits.MAX_STRING_LENGTH
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.NamespacedIdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec

/**
 * One creative-inventory entry (v7): an own tab ([target] = `null`, at most [ProtocolLimits.MAX_CREATIVE_TABS] of
 * them, shown in the client's placeholder tabs in order) or items added to a vanilla tab ([target] = its id, e.g.
 * `minecraft:ingredients`). [id] is a `namespace:path` key, [title] a Component as JSON, items are full stacks.
 */
public data class CreativeTabData(
    val id: String,
    val title: String,
    val icon: ItemStackData?,
    val items: List<ItemStackData>,
    val target: String?,
) {
    /** Wire codec: id, title, optional icon, items, optional target tab. */
    public companion object : Codec<CreativeTabData> {
        private val id = NamespacedIdCodec("creative tab id")
        private val title = StringCodec(MAX_STRING_LENGTH, "creative tab title")
        private val icon = ItemStackData.nullable()
        private val items = ItemStackData.list(MAX_CREATIVE_ITEMS, "creative items")
        private val target = NamespacedIdCodec("creative tab target").nullable()

        override fun encode(writer: ProtocolWriter, value: CreativeTabData) {
            id.encode(writer, value.id)
            title.encode(writer, value.title)
            icon.encode(writer, value.icon)
            items.encode(writer, value.items)
            target.encode(writer, value.target)
        }

        override fun decode(reader: ProtocolReader): CreativeTabData = CreativeTabData(
            id = id.decode(reader),
            title = title.decode(reader),
            icon = icon.decode(reader),
            items = items.decode(reader),
            target = target.decode(reader),
        )
    }
}
