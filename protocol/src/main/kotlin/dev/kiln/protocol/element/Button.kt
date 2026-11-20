package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.ProtocolLimits.MAX_STRING_LENGTH
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec

/** Clicking sends [ScreenClick]. */
public data class Button(
    override val id: String,
    override val bounds: Bounds,
    /** Component JSON. */
    val label: String,
    val enabled: Boolean = true,
    override val tooltip: String? = null,
) : Element {
    /** Wire codec: the element header, then the fields below. */
    public companion object : Codec<Button> {
        private val label = StringCodec(MAX_STRING_LENGTH, "label")

        override fun encode(writer: ProtocolWriter, value: Button) {
            ElementHeader.encode(writer, ElementHeader.of(value))
            label.encode(writer, value.label)
            writer.boolean(value.enabled)
        }

        override fun decode(reader: ProtocolReader): Button {
            val header = ElementHeader.decode(reader)
            return Button(
                id = header.id,
                bounds = header.bounds,
                label = label.decode(reader),
                enabled = reader.boolean(),
                tooltip = header.tooltip,
            )
        }
    }
}
