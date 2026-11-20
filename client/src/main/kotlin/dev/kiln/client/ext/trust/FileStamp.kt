package dev.kiln.client.ext.trust

import java.nio.file.Files
import java.nio.file.Path

/** Existence, modification time and size of a file: compared to notice changes made outside the game. */
internal data class FileStamp(val exists: Boolean, val modified: Long, val size: Long) {
    companion object {
        private val MISSING = FileStamp(false, 0, 0)

        fun of(file: Path): FileStamp = try {
            if (Files.isRegularFile(file)) FileStamp(true, Files.getLastModifiedTime(file).toMillis(), Files.size(file)) else MISSING
        } catch (_: Exception) {
            MISSING
        }
    }
}
