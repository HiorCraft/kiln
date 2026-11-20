package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_CHUNKS
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES
import dev.kiln.protocol.codec.ByteArrayCodec
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.IdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec

/**
 * Configuration phase: part [index] (0-based) of [total] of package [id]. Chunks of one package arrive in order;
 * every chunk but the last carries exactly [ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES] bytes, the last 1..that
 * many. [total] equals [ExtensionInfo.chunkCount]; the concatenation is the zip with the manifest's size and sha256.
 */
public class ExtensionChunk(public val id: String, public val index: Int, public val total: Int, public val data: ByteArray) :
    ClientboundMessage {

    override fun equals(other: Any?): Boolean =
        this === other || (other is ExtensionChunk && id == other.id && index == other.index && total == other.total &&
            data.contentEquals(other.data))

    override fun hashCode(): Int = ((id.hashCode() * 31 + index) * 31 + total) * 31 + data.contentHashCode()

    override fun toString(): String = "ExtensionChunk($id ${index + 1}/$total, ${data.size} bytes)"

    /** Wire codec: package id, total, index (`0 until total`), the non-empty data. */
    public companion object : Codec<ExtensionChunk> {
        private val total = RangedVarIntCodec(1, MAX_EXTENSION_CHUNKS, "chunk total")
        private val data = ByteArrayCodec(MAX_EXTENSION_CHUNK_BYTES, "extension chunk").validated(::checkNotEmpty)

        override fun encode(writer: ProtocolWriter, value: ExtensionChunk) {
            IdCodec.LUA_EXTENSION.encode(writer, value.id)
            total.encode(writer, value.total)
            indexCodec(value.total).encode(writer, value.index)
            data.encode(writer, value.data)
        }

        override fun decode(reader: ProtocolReader): ExtensionChunk {
            val id = IdCodec.LUA_EXTENSION.decode(reader)
            val total = total.decode(reader)
            val index = indexCodec(total).decode(reader)
            return ExtensionChunk(id, index, total, data.decode(reader))
        }

        private fun indexCodec(total: Int) = RangedVarIntCodec(0, total - 1, "chunk index")

        private fun checkNotEmpty(data: ByteArray) {
            if (data.isEmpty()) throw ProtocolException("Empty extension chunk")
        }
    }
}
