package dev.ambon.persistence

import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
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

class YamlPlayerRepository(
    private val rootDir: Path,
    private val metrics: GameMetrics = GameMetrics.noop(),
) : PlayerRepository {
    private val mapper = yamlMapper

    private val playersDir: Path = rootDir.resolve("players")
    private val nextIdFile: Path = rootDir.resolve("next_player_id.txt")
    private val nextId: AtomicLong by lazy { AtomicLong(loadNextId()) }

    // In-memory name→id index.  Built lazily on first findByName call (one-time directory scan),
    // then kept up-to-date by save() and create().  Eliminates repeated full-directory scans.
    private val nameIndex = ConcurrentHashMap<String, Long>()

    // In-memory authTokenHash→id index.  Populated alongside nameIndex so that
    // findByAuthTokenHash is O(1) instead of scanning every YAML file.
    private val authTokenIndex = ConcurrentHashMap<String, Long>()

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
                    return@timedLoad readRecord(pathFor(id))
                }
                null
            }
        }

    override suspend fun findById(id: PlayerId): PlayerRecord? =
        withContext(Dispatchers.IO) {
            metrics.timedLoad {
                val path = pathFor(id.value)
                readRecord(path)
            }
        }

    override suspend fun findByAuthTokenHash(hash: String): PlayerRecord? {
        if (hash.isBlank()) return null
        return withContext(Dispatchers.IO) {
            metrics.timedLoad {
                ensureNameIndexReady()
                val id = authTokenIndex[hash] ?: return@timedLoad null
                readRecord(pathFor(id))
            }
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
                    throw PersistenceException("Name already taken: '$nm'")
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

    override suspend fun findAll(): List<PlayerRecord> =
        withContext(Dispatchers.IO) {
            if (!playersDir.exists()) return@withContext emptyList()
            playersDir.listDirectoryEntries("*.yaml").mapNotNull { path ->
                runCatching { readRecord(path) }.getOrNull()
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
                val record = runCatching { readRecord(p) }.getOrNull() ?: return@forEach
                val id = record.id.value
                maxSeenId = maxOf(maxSeenId, id)
                val key = record.name.lowercase()
                val existingId = nameIndex[key]
                if (existingId != null) {
                    // Keep the lower ID (earlier created); log a warning so operators notice.
                    log.warn {
                        "Duplicate player name '${record.name}' found in files " +
                            "${pathFor(existingId).fileName} and ${p.fileName}. " +
                            "Keeping ID $existingId; ID $id is orphaned."
                    }
                } else {
                    nameIndex[key] = id
                }
                if (record.authTokenHash.isNotEmpty()) {
                    authTokenIndex[record.authTokenHash] = id
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

    private fun readRecord(path: Path): PlayerRecord? {
        if (!path.exists()) return null
        return try {
            mapper.readValue<PlayerRecord>(path.readText()).migrateDefaults()
        } catch (e: Exception) {
            throw PersistenceException("Failed to read player file: $path", e)
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
            throw PersistenceException("Failed to read next_player_id.txt", e)
        }
    }

    private fun persistNextId(value: Long) {
        // This file is tiny; atomic rename is fine.
        atomicWriteText(nextIdFile, value.toString())
    }

    private fun writePlayer(record: PlayerRecord) {
        atomicWriteText(pathFor(record.id.value), mapper.writeValueAsString(record))
        nameIndex[record.name.lowercase()] = record.id.value
        updateAuthTokenIndex(record)
    }

    /**
     * Keeps [authTokenIndex] in sync after a player write.  Removes any stale entry
     * that previously pointed to this player's ID (handles token rotation / revocation)
     * and inserts the new mapping if the record carries a non-empty hash.
     */
    private fun updateAuthTokenIndex(record: PlayerRecord) {
        val id = record.id.value
        // Remove any existing entry that maps *to* this player ID.  This is necessary when
        // the token hash changed (rotation) or was cleared (revocation).  ConcurrentHashMap
        // iteration is safe under concurrent reads; the scan is bounded by the number of
        // players who have ever issued a token — typically a small fraction of the population.
        authTokenIndex.entries.removeIf { it.value == id }
        if (record.authTokenHash.isNotEmpty()) {
            authTokenIndex[record.authTokenHash] = id
        }
    }
}
