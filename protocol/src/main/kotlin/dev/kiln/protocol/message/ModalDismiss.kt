package dev.kiln.protocol.message

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.IdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.ScreenIdCodec

/** Escape was pressed while [modalId] (the topmost [Modal]) was open; the screen stays open. */
public data class ModalDismiss(val screenId: Int, val modalId: String) : ServerboundMessage {
    /** Wire codec: screen id, modal id. */
    public companion object : Codec<ModalDismiss> {
        override fun encode(writer: ProtocolWriter, value: ModalDismiss) {
            ScreenIdCodec.encode(writer, value.screenId)
            IdCodec.ELEMENT.encode(writer, value.modalId)
        }

        override fun decode(reader: ProtocolReader): ModalDismiss =
            ModalDismiss(ScreenIdCodec.decode(reader), IdCodec.ELEMENT.decode(reader))
    }
}
