package dev.kiln.protocol.message

import dev.kiln.protocol.Ids
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_JSON_LENGTH
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec

/**
 * Play phase: a message on channel `<extension id>:<name>` ([channel]) between a Lua extension
 * (`kiln.net.channel(name)`) and a server plugin (`Extension.channel(name)`). [json] is one JSON value as
 * text (UTF-8 on the wire, at most [ProtocolLimits.MAX_EXTENSION_JSON_LENGTH] chars). The codec checks only the
 * channel and the length; both ends parse/validate it (nesting depth <= 16) and the server rate-limits it.
 */
public data class ExtensionMessage(val channel: ResourceId, val json: String) : ServerboundMessage, ClientboundMessage {
    public companion object {
        /** Client -> server messages, limited to the small serverbound payload. */
        public val SERVERBOUND_CODEC: Codec<ExtensionMessage> = ExtensionMessageCodec(MAX_EXTENSION_JSON_LENGTH)

        /** Server -> client messages, which may be larger. */
        public val CLIENTBOUND_CODEC: Codec<ExtensionMessage> =
            ExtensionMessageCodec(MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH)
    }
}

/** Channel `<extension id>:<name>`, then at most [maxJsonLength] chars of JSON. */
private class ExtensionMessageCodec(maxJsonLength: Int) : Codec<ExtensionMessage> {
    private val channel = ResourceId.codec("extension channel").validated(::checkChannel)
    private val json = StringCodec(maxJsonLength, "extension message json")

    override fun encode(writer: ProtocolWriter, value: ExtensionMessage) {
        channel.encode(writer, value.channel)
        json.encode(writer, value.json)
    }

    override fun decode(reader: ProtocolReader): ExtensionMessage =
        ExtensionMessage(channel.decode(reader), json.decode(reader))

    private fun checkChannel(channel: ResourceId) {
        if (!Ids.isValidLuaExtensionId(channel.namespace) || !Ids.isValidExtensionName(channel.path)) {
            throw ProtocolException("Invalid extension channel '$channel' (expected <extension id>:<name>)")
        }
    }
}
