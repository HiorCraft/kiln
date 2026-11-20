package dev.kiln.protocol.codec

import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_RESOURCE_ID_LENGTH
import dev.kiln.protocol.ResourceId

/** A `namespace:path` id kept as text; unlike [ResourceIdCodec] the namespace is mandatory. */
public class NamespacedIdCodec(private val what: String) : Codec<String> {
    private val text = StringCodec(MAX_RESOURCE_ID_LENGTH, what).validated(::checkNamespaced)

    override fun encode(writer: ProtocolWriter, value: String): Unit = text.encode(writer, value)

    override fun decode(reader: ProtocolReader): String = text.decode(reader)

    private fun checkNamespaced(id: String) {
        if (!id.contains(':') || ResourceId.tryParse(id) == null) throw ProtocolException("Invalid $what '$id'")
    }
}
