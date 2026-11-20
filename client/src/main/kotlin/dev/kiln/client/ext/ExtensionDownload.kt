package dev.kiln.client.ext

import dev.kiln.protocol.ExtensionChunk
import dev.kiln.protocol.ExtensionInfo
import dev.kiln.protocol.ProtocolLimits
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** SHA-256 helpers. */
object Sha256 {
    fun of(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun hex(hash: ByteArray): String = hash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Package cache `<gameDir>/kiln/cache/<sha256hex>.zip`. Entries are content-addressed: a file is only used if
 * its size and SHA-256 match what the server announced; a mismatching file is deleted.
 */
class ExtensionCache(private val dir: Path) {
    private val logger = LoggerFactory.getLogger("kiln")

    private fun path(info: ExtensionInfo): Path = dir.resolve(Sha256.hex(info.sha256) + ".zip")

    /** The cached zip of [info], or `null` if missing/invalid. */
    fun get(info: ExtensionInfo): ByteArray? {
        val file = path(info)
        if (!Files.isRegularFile(file)) return null
        return try {
            if (Files.size(file) != info.size.toLong()) {
                Files.deleteIfExists(file)
                return null
            }
            val bytes = Files.readAllBytes(file)
            if (Sha256.of(bytes).contentEquals(info.sha256)) bytes else {
                logger.warn("Cached package {} is corrupt, downloading again", file.fileName)
                Files.deleteIfExists(file)
                null
            }
        } catch (e: Exception) {
            logger.warn("Cannot read cached package {}", file, e)
            null
        }
    }

    /** Cached package files (`*.zip`, plus leftover `*.tmp`). */
    private fun files(): List<Path> = try {
        if (!Files.isDirectory(dir)) emptyList()
        else Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) && (it.fileName.toString().endsWith(".zip") || it.fileName.toString().endsWith(".tmp")) }.toList() }
    } catch (e: Exception) {
        logger.warn("Cannot list the package cache {}", dir, e)
        emptyList()
    }

    /** Total size of the cached packages in bytes. */
    fun sizeBytes(): Long = files().sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }

    /**
     * Deletes every cached package; returns how many files were deleted. Loaded extensions are unaffected (they run
     * from memory); the next join downloads the packages again.
     */
    fun clear(): Int {
        var deleted = 0
        for (file in files()) {
            try {
                if (Files.deleteIfExists(file)) deleted++
            } catch (e: Exception) {
                logger.warn("Cannot delete cached package {}", file, e)
            }
        }
        return deleted
    }

    /** Stores a verified zip (atomic replace). Failures are logged; the package still loads from memory. */
    fun put(info: ExtensionInfo, data: ByteArray) {
        val file = path(info)
        try {
            Files.createDirectories(dir)
            val tmp = dir.resolve(file.fileName.toString() + ".tmp")
            Files.write(tmp, data)
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            logger.warn("Cannot cache package {}", file, e)
        }
    }
}

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
