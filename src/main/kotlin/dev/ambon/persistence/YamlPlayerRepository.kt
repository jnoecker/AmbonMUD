package dev.ambon.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.metrics.GameMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PlayerPersistenceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class YamlPlayerRepository(
    private val rootDir: Path,
    private val metrics: GameMetrics = GameMetrics.noop(),
) : PlayerRepository {
    private val mapper: ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerModule(KotlinModule.Builder().build())

    private val playersDir: Path = rootDir.resolve("players")
    private val nextIdFile: Path = rootDir.resolve("next_player_id.txt")
    private val nextId: AtomicLong by lazy { AtomicLong(loadNextId()) }

    init {
        playersDir.createDirectories()
        if (!nextIdFile.exists()) {
            // start at 1
            nextIdFile.writeText("1")
        }
    }

    override suspend fun findByName(name: String): PlayerRecord? =
        withContext(Dispatchers.IO) {
            metrics.timedLoad {
                val target = name.trim()
                if (target.isEmpty()) return@timedLoad null

                // Simple scan. Fine for Phase 1. Later: index file or SQLite.
                playersDir.listDirectoryEntries("*.yaml").forEach { p ->
                    val dto = readPlayerDto(p) ?: return@forEach
                    if (dto.name.equals(target, ignoreCase = true)) {
                        return@timedLoad dto.toDomain()
                    }
                }
                null
            }
        }

    override suspend fun findById(id: PlayerId): PlayerRecord? =
        withContext(Dispatchers.IO) {
            metrics.timedLoad {
                val path = pathFor(id.value)
                readPlayerDto(path)?.toDomain()
            }
        }

    override suspend fun create(request: PlayerCreationRequest): PlayerRecord =
        withContext(Dispatchers.IO) {
            val nm = request.name.trim()
            require(nm.isNotEmpty()) { "name cannot be blank" }
            require(request.passwordHash.isNotEmpty()) { "passwordHash cannot be blank" }

            // Enforce unique name (case-insensitive) for now
            val existing = findByName(nm)
            if (existing != null) throw PlayerPersistenceException("Name already taken: '$nm'")

            val id = nextId.getAndIncrement()
            persistNextId(nextId.get())

            val record = request.toNewPlayerRecord(PlayerId(id))

            save(record)
            record
        }

    override suspend fun save(record: PlayerRecord): Unit =
        withContext(Dispatchers.IO) {
            metrics.timedSave {
                atomicWriteText(pathFor(record.id.value), mapper.writeValueAsString(PlayerDto.from(record)))
            }
        }

    // -------- internals --------

    private fun pathFor(id: Long): Path = playersDir.resolve(id.toString().padStart(20, '0') + ".yaml")

    private fun readPlayerDto(path: Path): PlayerDto? {
        if (!path.exists()) return null
        return try {
            mapper.readValue<PlayerDto>(path.readText())
        } catch (e: Exception) {
            throw PlayerPersistenceException("Failed to read player file: $path", e)
        }
    }

    private fun loadNextId(): Long {
        return try {
            if (!nextIdFile.exists()) return 1L
            nextIdFile
                .readText()
                .trim()
                .toLong()
                .coerceAtLeast(1L)
        } catch (e: Exception) {
            throw PlayerPersistenceException("Failed to read next_player_id.txt", e)
        }
    }

    private fun persistNextId(value: Long) {
        // This file is tiny; atomic rename is fine.
        atomicWriteText(nextIdFile, value.toString())
    }

    private fun atomicWriteText(
        path: Path,
        contents: String,
    ) {
        try {
            path.parent?.createDirectories()

            val tmp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
            Files.writeString(tmp, contents, StandardOpenOption.TRUNCATE_EXISTING)

            // Best-effort atomic move; on Windows this can fail if antivirus hooks, etc.
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw PlayerPersistenceException("Failed to write file atomically: $path", e)
        }
    }
}
