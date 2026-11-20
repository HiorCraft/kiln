package dev.kiln.protocol.element

import dev.kiln.protocol.ProtocolLimits.MAX_OPTION_LABEL_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_OPTION_VALUE_LENGTH
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec

public data class DropdownOption(
    val value: String,
    /** Component JSON. */
    val label: String,
) {
    /** Wire codec: [value], then [label]. */
    public companion object : Codec<DropdownOption> {
        private val value = StringCodec(MAX_OPTION_VALUE_LENGTH, "option value")
        private val label = StringCodec(MAX_OPTION_LABEL_LENGTH, "option label")

        override fun encode(writer: ProtocolWriter, value: DropdownOption) {
            this.value.encode(writer, value.value)
            label.encode(writer, value.label)
        }

        override fun decode(reader: ProtocolReader): DropdownOption =
            DropdownOption(value.decode(reader), label.decode(reader))
    }
}
