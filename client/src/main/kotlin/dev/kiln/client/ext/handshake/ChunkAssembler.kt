package dev.kiln.client.ext.handshake

import dev.kiln.client.ext.ExtensionPackageException
import dev.kiln.protocol.message.ExtensionChunk
import dev.kiln.protocol.message.ExtensionInfo
import dev.kiln.protocol.ProtocolLimits
import java.io.ByteArrayOutputStream

/**
 * Reassembles one package from its [ExtensionChunk]s and verifies it: chunks in order, `total` as announced, every
 * chunk but the last exactly [ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES], final size and SHA-256 as in the manifest.
 */
class ChunkAssembler(val info: ExtensionInfo) {
    // grows with the data that really arrives (a manifest's announced size alone allocates nothing)
    private val out = ByteArrayOutputStream(info.size.coerceIn(0, ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES))
    private var next = 0

    val isComplete: Boolean get() = next == info.chunkCount

    /** Adds [chunk]; returns the verified zip once the last chunk arrived, else `null`. */
    fun add(chunk: ExtensionChunk): ByteArray? {
        if (chunk.id != info.id) throw ExtensionPackageException("chunk for '${chunk.id}' while downloading '${info.id}'")
        if (isComplete) throw ExtensionPackageException("${info.id}: unexpected extra chunk")
        if (chunk.total != info.chunkCount) throw ExtensionPackageException("${info.id}: chunk count ${chunk.total}, expected ${info.chunkCount}")
        if (chunk.index != next) throw ExtensionPackageException("${info.id}: chunk ${chunk.index} out of order (expected $next)")
        val last = next == info.chunkCount - 1
        val max = ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES
        if (!last && chunk.data.size != max) throw ExtensionPackageException("${info.id}: chunk $next has ${chunk.data.size} bytes")
        if (last && (chunk.data.isEmpty() || chunk.data.size > max)) throw ExtensionPackageException("${info.id}: last chunk has ${chunk.data.size} bytes")
        if (out.size() + chunk.data.size > info.size) throw ExtensionPackageException("${info.id}: more data than announced")
        out.write(chunk.data)
        next++
        if (!last) return null
        val data = out.toByteArray()
        if (data.size != info.size) throw ExtensionPackageException("${info.id}: size ${data.size}, expected ${info.size}")
        if (!Sha256.of(data).contentEquals(info.sha256)) throw ExtensionPackageException("${info.id}: SHA-256 mismatch")
        return data
    }
}
