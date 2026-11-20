package dev.kiln.protocol.message

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.ScreenIdCodec

public data class CloseScreen(val screenId: Int) : ClientboundMessage {
    /** Wire codec: the screen id. */
    public companion object : Codec<CloseScreen> {
        override fun encode(writer: ProtocolWriter, value: CloseScreen): Unit =
            ScreenIdCodec.encode(writer, value.screenId)

        override fun decode(reader: ProtocolReader): CloseScreen = CloseScreen(ScreenIdCodec.decode(reader))
    }
}
