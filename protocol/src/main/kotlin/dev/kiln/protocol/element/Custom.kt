package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_ID_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_PROPERTIES
import dev.kiln.protocol.ProtocolLimits.MAX_PROPERTY_VALUE_LENGTH
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.PairCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec

/** Rendered by a client extension registered for [type]; unknown types are drawn as a placeholder. */
public data class Custom(
    override val id: String,
    override val bounds: Bounds,
    val type: ResourceId,
    val properties: Map<String, String> = emptyMap(),
    override val tooltip: String? = null,
) : Element {
    /** Wire codec: the element header, the widget type and its string properties (keys unique, order kept). */
    public companion object : Codec<Custom> {
        private val type = ResourceId.codec("custom type")
        private val properties = PairCodec(
            StringCodec(MAX_ID_LENGTH, "property key"),
            StringCodec(MAX_PROPERTY_VALUE_LENGTH, "property value"),
        ).list(MAX_PROPERTIES, "properties")

        override fun encode(writer: ProtocolWriter, value: Custom) {
            ElementHeader.encode(writer, ElementHeader.of(value))
            type.encode(writer, value.type)
            properties.encode(writer, value.properties.toList())
        }

        override fun decode(reader: ProtocolReader): Custom {
            val header = ElementHeader.decode(reader)
            val type = type.decode(reader)
            val properties = LinkedHashMap<String, String>()
            for ((key, value) in this.properties.decode(reader)) {
                if (properties.put(key, value) != null) throw ProtocolException("Duplicate property '$key'")
            }
            return Custom(header.id, header.bounds, type, properties, header.tooltip)
        }
    }
}
