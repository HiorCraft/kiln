package dev.kiln.protocol.message

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ElementCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.ScreenIdCodec
import dev.kiln.protocol.element.Element

/** Replaces the tree of an open screen; client state is kept for elements whose id still exists. */
public data class UpdateScreen(
    val screenId: Int,
    val root: Element,
    val fullscreen: Boolean = false,
) : ClientboundMessage {
    /** Wire codec: screen id, [fullscreen], then the element tree. */
    public companion object : Codec<UpdateScreen> {
        override fun encode(writer: ProtocolWriter, value: UpdateScreen) {
            ScreenIdCodec.encode(writer, value.screenId)
            writer.boolean(value.fullscreen)
            ElementCodec.encode(writer, value.root)
        }

        override fun decode(reader: ProtocolReader): UpdateScreen {
            val screenId = ScreenIdCodec.decode(reader)
            val fullscreen = reader.boolean()
            return UpdateScreen(screenId, ElementCodec.decode(reader), fullscreen)
        }
    }
}
