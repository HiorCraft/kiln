package dev.kiln.protocol

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

/**
 * A `namespace:path` identifier (Minecraft `Identifier` / Adventure `Key` without the dependency).
 * Same character rules as Minecraft.
 */
@ConsistentCopyVisibility
public data class ResourceId private constructor(val namespace: String, val path: String) {
    override fun toString(): String = "$namespace:$path"

    /** Also the wire codec: `namespace:path` as text; [codec] names the id in error messages. */
    public companion object : Codec<ResourceId> by ResourceIdCodec("resource id") {
        private val NAMESPACE = Regex("[a-z0-9_.-]+")
        private val PATH = Regex("[a-z0-9_./-]+")

        /** Creates an id, throwing [IllegalArgumentException] for invalid characters or length. */
        public fun of(namespace: String, path: String): ResourceId {
            require(namespace.length + 1 + path.length <= ProtocolLimits.MAX_RESOURCE_ID_LENGTH) { "Resource id too long" }
            require(namespace.matches(NAMESPACE)) { "Invalid namespace '$namespace'" }
            require(path.matches(PATH)) { "Invalid path '$path'" }
            return ResourceId(namespace, path)
        }

        /** Parses `namespace:path`; a missing namespace means `minecraft`. */
        public fun parse(value: String): ResourceId {
            val colon = value.indexOf(':')
            return if (colon < 0) of("minecraft", value) else of(value.substring(0, colon), value.substring(colon + 1))
        }

        /** Like [parse] but returns `null` instead of throwing. */
        public fun tryParse(value: String): ResourceId? = runCatching { parse(value) }.getOrNull()

        /** The wire codec with [what] naming the id in error messages. */
        public fun codec(what: String): Codec<ResourceId> = ResourceIdCodec(what)
    }
}

private class ResourceIdCodec(private val what: String) : Codec<ResourceId> {
    override fun encode(writer: ProtocolWriter, value: ResourceId): Unit =
        writer.string(value.toString(), ProtocolLimits.MAX_RESOURCE_ID_LENGTH, what)

    override fun decode(reader: ProtocolReader): ResourceId {
        val raw = reader.string(ProtocolLimits.MAX_RESOURCE_ID_LENGTH, what)
        return ResourceId.tryParse(raw) ?: throw ProtocolException("Invalid $what '$raw'")
    }
}
