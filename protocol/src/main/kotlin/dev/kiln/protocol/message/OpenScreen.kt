package dev.kiln.protocol.message

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ElementCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.ScreenIdCodec
import dev.kiln.protocol.element.Element

/**
 * Opens a screen; an already open kiln screen is replaced.
 * [fullscreen]: the root was rendered for the client's [Viewport]; the client places it at its own x/y (no
 * centering) and, until the next update arrives after a resize, stretches the root's background to the window.
 */
public data class OpenScreen(
    val screenId: Int,
    val root: Element,
    val fullscreen: Boolean = false,
) : ClientboundMessage {
    /** Wire codec: screen id, [fullscreen], then the element tree. */
    public companion object : Codec<OpenScreen> {
        override fun encode(writer: ProtocolWriter, value: OpenScreen) {
            ScreenIdCodec.encode(writer, value.screenId)
            writer.boolean(value.fullscreen)
            ElementCodec.encode(writer, value.root)
        }

        override fun decode(reader: ProtocolReader): OpenScreen {
            val screenId = ScreenIdCodec.decode(reader)
            val fullscreen = reader.boolean()
            return OpenScreen(screenId, ElementCodec.decode(reader), fullscreen)
        }
    }
}
