package dev.kiln.server.runtime.extension

import java.io.IOException
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
        val budget = ReadBudget()
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
    fun fromZip(zipPath: Path, id: String): Map<String, ByteArray> =
        stripPrefix(readZip(zipPath, "zip $zipPath") { it }, "$id/")

    /** Folder [resourcePath] inside the jar at [jarPath] (a plugin jar). */
    fun fromJar(jarPath: Path, resourcePath: String): Map<String, ByteArray> {
        val prefix = resourcePath.trim('/') + "/"
        val result = readZip(jarPath, "$jarPath") { name -> name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) }
        if (result.isEmpty()) throw InvalidExtensionException("No files under '$prefix' in $jarPath")
        return result
    }

    /** The files of the zip at [zipPath] that [pathOf] maps to a package path (`null` = skip), within the budget. */
    private fun readZip(zipPath: Path, description: String, pathOf: (entryName: String) -> String?): Map<String, ByteArray> {
        val budget = ReadBudget()
        val result = LinkedHashMap<String, ByteArray>()
        try {
            ZipFile(zipPath.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val path = pathOf(entry.name) ?: continue
                    budget.file(path)
                    result[path] = zip.getInputStream(entry).use { budget.read(path, it) }
                }
            }
        } catch (e: IOException) {
            throw InvalidExtensionException("Cannot read $description: ${e.message}", e)
        }
        return result
    }

    internal fun stripPrefix(files: Map<String, ByteArray>, prefix: String): Map<String, ByteArray> =
        if (ExtensionPackager.MANIFEST_FILE !in files && files.isNotEmpty() && files.keys.all { it.startsWith(prefix) }) {
            files.mapKeys { it.key.removePrefix(prefix) }
        } else {
            files
        }
}
