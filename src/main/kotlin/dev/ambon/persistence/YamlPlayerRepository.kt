package dev.ambon.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.ids.RoomId
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
) : PlayerRepository {
    // DTO uses plain types so Jackson doesn't need special Map key handling etc.
    private data class PlayerFile(
        val id: Long,
        val name: String,
        val roomId: String,
        val createdAtEpochMs: Long,
        val lastSeenEpochMs: Long,
        val ansiEnabled: Boolean = false,
    )

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
            val target = name.trim()
            if (target.isEmpty()) return@withContext null

            // Simple scan. Fine for Phase 1. Later: index file or SQLite.
            playersDir.listDirectoryEntries("*.yaml").forEach { p ->
                val pf = readPlayerFile(p) ?: return@forEach
                if (pf.name.equals(target, ignoreCase = true)) {
                    return@withContext pf.toDomain()
                }
            }
            null
        }

    override suspend fun findById(id: PlayerId): PlayerRecord? =
        withContext(Dispatchers.IO) {
            val path = pathFor(id.value)
            val pf = readPlayerFile(path) ?: return@withContext null
            pf.toDomain()
        }

    override suspend fun create(
        name: String,
        startRoomId: RoomId,
        nowEpochMs: Long,
    ): PlayerRecord =
        withContext(Dispatchers.IO) {
            val nm = name.trim()
            require(nm.isNotEmpty()) { "name cannot be blank" }

            // Enforce unique name (case-insensitive) for now
            val existing = findByName(nm)
            if (existing != null) throw PlayerPersistenceException("Name already taken: '$nm'")

            val id = nextId.getAndIncrement()
            persistNextId(nextId.get())

            val record =
                PlayerRecord(
                    id = PlayerId(id),
                    name = nm,
                    roomId = startRoomId,
                    createdAtEpochMs = nowEpochMs,
                    lastSeenEpochMs = nowEpochMs,
                    ansiEnabled = false,
                )

            save(record)
            record
        }

    override suspend fun save(record: PlayerRecord): Unit =
        withContext(Dispatchers.IO) {
            val pf =
                PlayerFile(
                    id = record.id.value,
                    name = record.name,
                    roomId = record.roomId.value,
                    createdAtEpochMs = record.createdAtEpochMs,
                    lastSeenEpochMs = record.lastSeenEpochMs,
                    ansiEnabled = record.ansiEnabled,
                )

            val outPath = pathFor(record.id.value)
            atomicWriteText(outPath, mapper.writeValueAsString(pf))
        }

    override suspend fun delete(id: PlayerId): Unit =
        withContext(Dispatchers.IO) {
            val path = pathFor(id.value)
            try {
                Files.deleteIfExists(path)
            } catch (e: Exception) {
                throw PlayerPersistenceException("Failed to delete player file: $path", e)
            }
        }

    // -------- internals --------

    private fun PlayerFile.toDomain(): PlayerRecord =
        PlayerRecord(
            id = PlayerId(id),
            name = name,
            roomId = RoomId(roomId),
            createdAtEpochMs = createdAtEpochMs,
            lastSeenEpochMs = lastSeenEpochMs,
            ansiEnabled = ansiEnabled,
        )

    private fun pathFor(id: Long): Path = playersDir.resolve(id.toString().padStart(20, '0') + ".yaml")

    private fun readPlayerFile(path: Path): PlayerFile? {
        if (!path.exists()) return null
        return try {
            mapper.readValue<PlayerFile>(path.readText())
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
