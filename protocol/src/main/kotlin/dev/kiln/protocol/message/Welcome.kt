package dev.kiln.protocol.message

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.VarIntCodec

/** Configuration phase: answer to [Hello]; also sent on a version mismatch right before the disconnect. */
public data class Welcome(val protocolVersion: Int) : ClientboundMessage {
    /** Wire codec: the protocol version. */
    public companion object : Codec<Welcome> {
        override fun encode(writer: ProtocolWriter, value: Welcome): Unit =
            VarIntCodec.encode(writer, value.protocolVersion)

        override fun decode(reader: ProtocolReader): Welcome = Welcome(VarIntCodec.decode(reader))
    }
}
