package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolLimits.MAX_SIZE
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec

/**
 * The client's GUI-scaled window size (`Window.getGuiScaledWidth/Height`). Sent right after [Hello] and whenever it
 * changes (window resize, GUI scale change, fullscreen toggle). The server re-renders the open screen on change.
 */
public data class Viewport(val width: Int, val height: Int) : ServerboundMessage {
    /** Wire codec: width, then height, each 1..[MAX_SIZE]. */
    public companion object : Codec<Viewport> {
        private val width = RangedVarIntCodec(1, MAX_SIZE, "viewport width")
        private val height = RangedVarIntCodec(1, MAX_SIZE, "viewport height")

        override fun encode(writer: ProtocolWriter, value: Viewport) {
            width.encode(writer, value.width)
            height.encode(writer, value.height)
        }

        override fun decode(reader: ProtocolReader): Viewport = Viewport(width.decode(reader), height.decode(reader))
    }
}
