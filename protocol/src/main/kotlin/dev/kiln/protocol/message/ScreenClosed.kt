package dev.kiln.protocol.message

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.ScreenIdCodec

/** The screen was closed by the player, or a server-side [CloseScreen] is acknowledged. */
public data class ScreenClosed(val screenId: Int) : ServerboundMessage {
    /** Wire codec: the screen id. */
    public companion object : Codec<ScreenClosed> {
        override fun encode(writer: ProtocolWriter, value: ScreenClosed): Unit =
            ScreenIdCodec.encode(writer, value.screenId)

        override fun decode(reader: ProtocolReader): ScreenClosed = ScreenClosed(ScreenIdCodec.decode(reader))
    }
}
