package dev.ambon.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

private val log = KotlinLogging.logger {}

class YamlWorldStateRepository(
    private val rootDir: Path,
) : WorldStateRepository {
    private val mapper: ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerModule(KotlinModule.Builder().build())

    private val stateFile: Path = rootDir.resolve("world_state.yml")

    init {
        rootDir.createDirectories()
    }

    override suspend fun load(): WorldStateSnapshot? =
        withContext(Dispatchers.IO) {
            if (!stateFile.exists()) return@withContext null
            try {
                mapper.readValue<WorldStateSnapshot>(stateFile.readText())
            } catch (e: Exception) {
                log.warn(e) { "Failed to load world state from $stateFile — starting fresh" }
                null
            }
        }

    override suspend fun save(snapshot: WorldStateSnapshot): Unit =
        withContext(Dispatchers.IO) {
            try {
                val contents = mapper.writeValueAsString(snapshot)
                atomicWriteText(stateFile, contents)
            } catch (e: Exception) {
                log.error(e) { "Failed to save world state to $stateFile" }
            }
        }

    private fun atomicWriteText(
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
            throw RuntimeException("Failed to write file atomically: $path", e)
        }
    }
}
