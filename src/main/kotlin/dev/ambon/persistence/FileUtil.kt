package dev.ambon.persistence

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories

/**
 * Writes [contents] to [path] atomically: writes to a temp file first,
 * then renames it into place. Falls back to a non-atomic move if the
 * file system does not support atomic rename.
 *
 * @throws PlayerPersistenceException if the write fails for any I/O reason.
 */
internal fun atomicWriteText(
    path: Path,
    contents: String,
) {
    try {
        path.parent?.createDirectories()
        val tmp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        Files.writeString(tmp, contents, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: IOException) {
        throw PlayerPersistenceException("Failed to write file atomically: $path", e)
    }
}
