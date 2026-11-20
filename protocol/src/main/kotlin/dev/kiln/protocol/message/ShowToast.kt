package dev.kiln.protocol.message

import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.ProtocolLimits.MAX_STRING_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_TOAST_MILLIS
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec
import dev.kiln.protocol.codec.StringCodec

/**
 * Shows a notification toast (top right, vanilla toast stack), independent of any screen.
 * [title] / [text]: component JSON; [icon]: optional item drawn at the left.
 */
public data class ShowToast(
    val title: String,
    val text: String? = null,
    val icon: ItemStackData? = null,
    val displayMillis: Int = 5_000,
) : ClientboundMessage {
    /** Wire codec: title, optional text, optional icon, display time (1..[MAX_TOAST_MILLIS]). */
    public companion object : Codec<ShowToast> {
        private val title = StringCodec(MAX_STRING_LENGTH, "toast title")
        private val text = StringCodec(MAX_STRING_LENGTH, "toast text").nullable()
        private val icon = ItemStackData.nullable()
        private val displayMillis = RangedVarIntCodec(1, MAX_TOAST_MILLIS, "toast display time")

        override fun encode(writer: ProtocolWriter, value: ShowToast) {
            title.encode(writer, value.title)
            text.encode(writer, value.text)
            icon.encode(writer, value.icon)
            displayMillis.encode(writer, value.displayMillis)
        }

        override fun decode(reader: ProtocolReader): ShowToast = ShowToast(
            title = title.decode(reader),
            text = text.decode(reader),
            icon = icon.decode(reader),
            displayMillis = displayMillis.decode(reader),
        )
    }
}
