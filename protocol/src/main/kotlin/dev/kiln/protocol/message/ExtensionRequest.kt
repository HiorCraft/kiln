package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolLimits.MAX_MANIFEST_EXTENSIONS
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.IdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.requireDistinct

/**
 * Configuration phase, answer to [ExtensionManifest] (exactly once): the [ids] whose package the client does not
 * have in its cache (by sha256) and wants streamed as [ExtensionChunk]s. May be empty. Every id must be listed in
 * the manifest, no duplicates.
 */
public data class ExtensionRequest(val ids: List<String>) : ServerboundMessage {
    /** Wire codec: the distinct requested extension ids. */
    public companion object : Codec<ExtensionRequest> {
        private val ids = IdCodec.LUA_EXTENSION.list(MAX_MANIFEST_EXTENSIONS, "requested extensions")
            .validated { ids -> requireDistinct(ids) { "Duplicate requested extension '$it'" } }

        override fun encode(writer: ProtocolWriter, value: ExtensionRequest): Unit = ids.encode(writer, value.ids)

        override fun decode(reader: ProtocolReader): ExtensionRequest = ExtensionRequest(ids.decode(reader))
    }
}
