package dev.ambon.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

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

    // In-memory name→id index.  Built lazily on first findByName call (one-time directory scan),
    // then kept up-to-date by save() and create().  Eliminates repeated full-directory scans.
    private val nameIndex = ConcurrentHashMap<String, Long>()
    private val nameIndexReady = AtomicBoolean(false)
    private val createLock = ReentrantLock()

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

                ensureNameIndexReady()

                val id = nameIndex[target.lowercase()]
                if (id != null) {
                    return@timedLoad readPlayerDto(pathFor(id))?.toDomain()
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

            createLock.withLock {
                ensureNameIndexReady()
                if (nameIndex.containsKey(nm.lowercase())) {
                    throw PlayerPersistenceException("Name already taken: '$nm'")
                }

                val id = nextId.getAndIncrement()
                persistNextId(nextId.get())

                val record = request.toNewPlayerRecord(PlayerId(id))
                writePlayer(record)
                record
            }
        }

    override suspend fun save(record: PlayerRecord): Unit =
        withContext(Dispatchers.IO) {
            metrics.timedSave {
                writePlayer(record)
            }
        }

    // -------- internals --------

    /**
     * Builds the name index from disk exactly once.  After this call [nameIndex] reflects all
     * persisted players.  Subsequent calls are no-ops.
     *
     * Files are scanned in ascending filename order (zero-padded numeric IDs → alphabetical = numeric).
     * When two files share a name the lower ID (earlier created) wins and a warning is logged.
     *
     * After the scan, [nextId] is bumped to at least `maxSeenId + 1` so that a stale or missing
     * `next_player_id.txt` can never cause a new player to be written over an existing file.
     */
    private fun ensureNameIndexReady() {
        if (nameIndexReady.get()) return
        // Double-checked: if another thread beat us here, the index is already populated.
        synchronized(nameIndexReady) {
            if (nameIndexReady.get()) return
            val files = playersDir.listDirectoryEntries("*.yaml").sortedBy { it.fileName.toString() }
            var maxSeenId = 0L
            files.forEach { p ->
                val dto = runCatching { readPlayerDto(p) }.getOrNull() ?: return@forEach
                maxSeenId = maxOf(maxSeenId, dto.id)
                val key = dto.name.lowercase()
                val existingId = nameIndex[key]
                if (existingId != null) {
                    // Keep the lower ID (earlier created); log a warning so operators notice.
                    log.warn {
                        "Duplicate player name '${dto.name}' found in files " +
                            "${pathFor(existingId).fileName} and ${p.fileName}. " +
                            "Keeping ID $existingId; ID ${dto.id} is orphaned."
                    }
                } else {
                    nameIndex[key] = dto.id
                }
            }
            // Guard against a stale or missing next_player_id.txt falling behind actual file IDs.
            if (maxSeenId >= nextId.get()) {
                val corrected = maxSeenId + 1
                log.warn {
                    "next_player_id.txt value ${nextId.get()} is behind max existing player ID " +
                        "$maxSeenId; correcting to $corrected to prevent file overwrites."
                }
                nextId.set(corrected)
                persistNextId(corrected)
            }
            nameIndexReady.set(true)
        }
    }

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

    private fun writePlayer(record: PlayerRecord) {
        atomicWriteText(pathFor(record.id.value), mapper.writeValueAsString(PlayerDto.from(record)))
        nameIndex[record.name.lowercase()] = record.id.value
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
