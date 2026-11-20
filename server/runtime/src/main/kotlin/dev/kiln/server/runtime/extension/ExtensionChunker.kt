package dev.kiln.server.runtime.extension

import dev.kiln.protocol.message.ExtensionChunk
import dev.kiln.protocol.ProtocolLimits

/** Splits a package zip into [ExtensionChunk]s of at most [ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES]. */
object ExtensionChunker {
    fun split(id: String, zip: ByteArray, chunkSize: Int = ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES): List<ExtensionChunk> {
        require(zip.isNotEmpty()) { "Empty package" }
        require(chunkSize in 1..ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES) { "Invalid chunk size $chunkSize" }
        val total = (zip.size + chunkSize - 1) / chunkSize
        return List(total) { index ->
            val from = index * chunkSize
            ExtensionChunk(id, index, total, zip.copyOfRange(from, minOf(zip.size, from + chunkSize)))
        }
    }
}
