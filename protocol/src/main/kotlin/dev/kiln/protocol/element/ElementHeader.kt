package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.ProtocolLimits.MAX_STRING_LENGTH
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.IdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec

/** The fields every element starts with on the wire. */
internal class ElementHeader(val id: String, val bounds: Bounds, val tooltip: String?) {
    /** Wire codec: id, bounds, optional tooltip. */
    companion object : Codec<ElementHeader> {
        private val tooltip = StringCodec(MAX_STRING_LENGTH, "tooltip").nullable()

        fun of(element: Element): ElementHeader = ElementHeader(element.id, element.bounds, element.tooltip)

        override fun encode(writer: ProtocolWriter, value: ElementHeader) {
            IdCodec.ELEMENT.encode(writer, value.id)
            Bounds.encode(writer, value.bounds)
            tooltip.encode(writer, value.tooltip)
        }

        override fun decode(reader: ProtocolReader): ElementHeader =
            ElementHeader(IdCodec.ELEMENT.decode(reader), Bounds.decode(reader), tooltip.decode(reader))
    }
}
