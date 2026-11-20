package dev.kiln.protocol.message

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.IdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

public data class KeyPressed(val keyId: String) : ServerboundMessage {
    /** Wire codec: the keybind id. */
    public companion object : Codec<KeyPressed> {
        override fun encode(writer: ProtocolWriter, value: KeyPressed): Unit =
            IdCodec.KEYBIND.encode(writer, value.keyId)

        override fun decode(reader: ProtocolReader): KeyPressed = KeyPressed(IdCodec.KEYBIND.decode(reader))
    }
}
