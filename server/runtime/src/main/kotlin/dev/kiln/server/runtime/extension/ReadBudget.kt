package dev.kiln.server.runtime.extension

import java.io.InputStream
import java.nio.file.Path

/** Caps file count, path length and total bytes while a package is read (before [ExtensionPackager] validates it). */
internal class ReadBudget {
    private var files = 0
    private var bytes = 0L

    fun file(path: String) {
        if (++files > ExtensionPackager.MAX_FILES) throw InvalidExtensionException("Too many files (> ${ExtensionPackager.MAX_FILES})")
        if (path.length > ExtensionPackager.MAX_PATH_LENGTH * 2) throw InvalidExtensionException("Path too long: $path")
    }

    fun read(path: String, input: InputStream): ByteArray {
        val limit = ExtensionPackager.MAX_UNCOMPRESSED_BYTES - bytes
        val data = input.readNBytes((limit + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        bytes += data.size
        if (bytes > ExtensionPackager.MAX_UNCOMPRESSED_BYTES) {
            throw InvalidExtensionException("Package too large uncompressed (> ${ExtensionPackager.MAX_UNCOMPRESSED_BYTES} bytes, at $path)")
        }
        return data
    }
}
