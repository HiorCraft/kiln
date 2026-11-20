package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_STRING_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_TEXT_FIELD_LENGTH
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec
import dev.kiln.protocol.codec.StringCodec

/**
 * Editable text. The client applies [value] when the element is new or the server changed the value;
 * otherwise the local content is kept. Edits are reported via [ScreenInput]. With [numeric] set, the client only
 * allows numeric input and reports only values the range [accepts][NumericRange.accepts] (or the empty string).
 */
public data class TextField(
    override val id: String,
    override val bounds: Bounds,
    val value: String = "",
    /** Component JSON. */
    val placeholder: String? = null,
    val maxLength: Int = 256,
    val numeric: NumericRange? = null,
    override val tooltip: String? = null,
) : Element {
    /** Wire codec: the element header, then the fields below; the value must fit `maxLength` and [numeric]. */
    public companion object : Codec<TextField> {
        private val maxLength = RangedVarIntCodec(1, MAX_TEXT_FIELD_LENGTH, "max length")
        private val placeholder = StringCodec(MAX_STRING_LENGTH, "placeholder").nullable()
        private val numeric = NumericRange.nullable()

        override fun encode(writer: ProtocolWriter, value: TextField) {
            ElementHeader.encode(writer, ElementHeader.of(value))
            maxLength.encode(writer, value.maxLength)
            writer.string(value.value, value.maxLength, "text field value")
            placeholder.encode(writer, value.placeholder)
            numeric.encode(writer, value.numeric)
            checkNumericValue(value.numeric, value.value)
        }

        override fun decode(reader: ProtocolReader): TextField {
            val header = ElementHeader.decode(reader)
            val maxLength = maxLength.decode(reader)
            val value = reader.string(maxLength, "text field value")
            val placeholder = placeholder.decode(reader)
            val numeric = numeric.decode(reader)
            checkNumericValue(numeric, value)
            return TextField(header.id, header.bounds, value, placeholder, maxLength, numeric, header.tooltip)
        }

        private fun checkNumericValue(range: NumericRange?, value: String) {
            if (range != null && value.isNotEmpty() && !range.accepts(value)) {
                throw ProtocolException("Text field value '$value' outside $range")
            }
        }
    }
}
