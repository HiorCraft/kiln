package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.Color
import dev.kiln.protocol.ProtocolLimits.MAX_STRING_LENGTH
import dev.kiln.protocol.TextAlign
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec

public data class Text(
    override val id: String,
    override val bounds: Bounds,
    /** Component JSON. */
    val text: String,
    val color: Color = Color.WHITE,
    val align: TextAlign = TextAlign.LEFT,
    val shadow: Boolean = true,
    override val tooltip: String? = null,
) : Element {
    /** Wire codec: the element header, then the fields below. */
    public companion object : Codec<Text> {
        private val text = StringCodec(MAX_STRING_LENGTH, "text")

        override fun encode(writer: ProtocolWriter, value: Text) {
            ElementHeader.encode(writer, ElementHeader.of(value))
            text.encode(writer, value.text)
            Color.encode(writer, value.color)
            TextAlign.encode(writer, value.align)
            writer.boolean(value.shadow)
        }

        override fun decode(reader: ProtocolReader): Text {
            val header = ElementHeader.decode(reader)
            return Text(
                id = header.id,
                bounds = header.bounds,
                text = text.decode(reader),
                color = Color.decode(reader),
                align = TextAlign.decode(reader),
                shadow = reader.boolean(),
                tooltip = header.tooltip,
            )
        }
    }
}
