package dev.kiln.client.ext.handshake

import dev.kiln.protocol.message.ExtensionInfo
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Package cache `<gameDir>/kiln/cache/<sha256hex>.zip`. Entries are content-addressed: a file is only used if
 * its size and SHA-256 match what the server announced; a mismatching file is deleted.
 */
class ExtensionCache(
    private val dir: Path,
    /** Above this the least recently used packages are deleted after each [put] (a server could change every join). */
    private val maxBytes: Long = 512L * 1024 * 1024,
) {
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
            if (Sha256.of(bytes).contentEquals(info.sha256)) {
                // "last used" for the size limit
                runCatching { Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())) }
                bytes
            } else {
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
        trim(keep = file)
    }

    /** Deletes the least recently used packages (oldest modification time) until the cache fits [maxBytes]. */
    private fun trim(keep: Path) {
        val entries = files().mapNotNull { f -> runCatching { Triple(f, Files.size(f), Files.getLastModifiedTime(f).toMillis()) }.getOrNull() }
        var total = entries.sumOf { it.second }
        for ((file, size, _) in entries.sortedBy { it.third }) {
            if (total <= maxBytes) break
            if (file == keep) continue
            try {
                if (Files.deleteIfExists(file)) total -= size
            } catch (e: Exception) {
                logger.warn("Cannot delete cached package {}", file, e)
            }
        }
    }
}
