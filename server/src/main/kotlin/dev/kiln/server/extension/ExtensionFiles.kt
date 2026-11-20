package dev.kiln.server.extension

import dev.kiln.protocol.ExtensionChunk
import dev.kiln.protocol.ProtocolLimits
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * Reads the raw files of an extension package from a directory, a zip or a folder inside a jar. Every reader caps
 * the amount of data at [ExtensionPackager.MAX_UNCOMPRESSED_BYTES] (+ file count) before validation runs.
 */
object ExtensionFiles {

    fun fromDirectory(dir: Path): Map<String, ByteArray> {
        if (!dir.isDirectory()) throw InvalidExtensionException("Not a directory: $dir")
        val budget = Budget()
        val result = LinkedHashMap<String, ByteArray>()
        Files.walk(dir).use { stream ->
            for (file in stream) {
                if (file.isDirectory()) continue
                if (!file.isRegularFile()) throw InvalidExtensionException("Not a regular file: $file")
                val path = file.relativeTo(dir).joinToString("/") { it.toString() }
                budget.file(path)
                result[path] = Files.newInputStream(file).use { budget.read(path, it) }
            }
        }
        return result
    }

    /** Zip with files at the root, or all of them inside one top-level `<id>/` folder (stripped). */
    fun fromZip(zipPath: Path, id: String): Map<String, ByteArray> {
        val budget = Budget()
        val raw = LinkedHashMap<String, ByteArray>()
        try {
            ZipFile(zipPath.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    budget.file(entry.name)
                    raw[entry.name] = zip.getInputStream(entry).use { budget.read(entry.name, it) }
                }
            }
        } catch (e: IOException) {
            throw InvalidExtensionException("Cannot read zip $zipPath: ${e.message}", e)
        }
        return stripPrefix(raw, "$id/")
    }

    /** Folder [resourcePath] inside the jar at [jarPath] (a plugin jar). */
    fun fromJar(jarPath: Path, resourcePath: String): Map<String, ByteArray> {
        val prefix = resourcePath.trim('/') + "/"
        val budget = Budget()
        val result = LinkedHashMap<String, ByteArray>()
        try {
            ZipFile(jarPath.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                    val path = entry.name.removePrefix(prefix)
                    budget.file(path)
                    result[path] = zip.getInputStream(entry).use { budget.read(path, it) }
                }
            }
        } catch (e: IOException) {
            throw InvalidExtensionException("Cannot read $jarPath: ${e.message}", e)
        }
        if (result.isEmpty()) throw InvalidExtensionException("No files under '$prefix' in $jarPath")
        return result
    }

    internal fun stripPrefix(files: Map<String, ByteArray>, prefix: String): Map<String, ByteArray> =
        if (ExtensionPackager.MANIFEST_FILE !in files && files.isNotEmpty() && files.keys.all { it.startsWith(prefix) }) {
            files.mapKeys { it.key.removePrefix(prefix) }
        } else {
            files
        }

    private class Budget {
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
}

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
