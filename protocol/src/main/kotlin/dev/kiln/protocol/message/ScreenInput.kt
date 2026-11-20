package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolLimits.MAX_TEXT_FIELD_LENGTH
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.IdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.ScreenIdCodec
import dev.kiln.protocol.codec.StringCodec

/**
 * New value of an input element:
 * - [TextField]: its content (throttled while typing, immediately on enter); numeric fields only valid numbers or "".
 * - [Dropdown]: the chosen [DropdownOption.value].
 * - [Checkbox]: `"true"` / `"false"`.
 * - [Slider]: the new value as decimal number.
 */
public data class ScreenInput(val screenId: Int, val elementId: String, val value: String) : ServerboundMessage {
    /** Wire codec: screen id, element id, value. */
    public companion object : Codec<ScreenInput> {
        private val input = StringCodec(MAX_TEXT_FIELD_LENGTH, "input value")

        override fun encode(writer: ProtocolWriter, value: ScreenInput) {
            ScreenIdCodec.encode(writer, value.screenId)
            IdCodec.ELEMENT.encode(writer, value.elementId)
            input.encode(writer, value.value)
        }

        override fun decode(reader: ProtocolReader): ScreenInput =
            ScreenInput(ScreenIdCodec.decode(reader), IdCodec.ELEMENT.decode(reader), input.decode(reader))
    }
}
