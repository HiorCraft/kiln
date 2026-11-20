package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.ProtocolLimits.MAX_STRING_LENGTH
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec

/** Boolean input; toggling sends [ScreenInput] with `"true"` / `"false"`. */
public data class Checkbox(
    override val id: String,
    override val bounds: Bounds,
    val checked: Boolean,
    /** Component JSON, drawn next to the box. */
    val label: String? = null,
    val style: CheckboxStyle = CheckboxStyle.CHECKBOX,
    val enabled: Boolean = true,
    override val tooltip: String? = null,
) : Element {
    /** Wire codec: the element header, then the fields below. */
    public companion object : Codec<Checkbox> {
        private val label = StringCodec(MAX_STRING_LENGTH, "label").nullable()

        override fun encode(writer: ProtocolWriter, value: Checkbox) {
            ElementHeader.encode(writer, ElementHeader.of(value))
            writer.boolean(value.checked)
            label.encode(writer, value.label)
            CheckboxStyle.encode(writer, value.style)
            writer.boolean(value.enabled)
        }

        override fun decode(reader: ProtocolReader): Checkbox {
            val header = ElementHeader.decode(reader)
            return Checkbox(
                id = header.id,
                bounds = header.bounds,
                checked = reader.boolean(),
                label = label.decode(reader),
                style = CheckboxStyle.decode(reader),
                enabled = reader.boolean(),
                tooltip = header.tooltip,
            )
        }
    }
}
