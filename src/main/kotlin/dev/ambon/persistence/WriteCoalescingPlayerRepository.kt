package dev.ambon.persistence

import dev.ambon.domain.character.PlayerClass
import dev.ambon.domain.character.PlayerRace
import dev.ambon.domain.ids.RoomId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = KotlinLogging.logger {}

/**
 * Wraps a [PlayerRepository] delegate, intercepting [save] to mark records dirty
 * in an in-memory cache instead of writing immediately.
 * Reads check the cache first, falling through to the delegate on miss.
 * Call [flushDirty] or [flushAll] to write dirty records to the delegate.
 */
class WriteCoalescingPlayerRepository(
    private val delegate: PlayerRepository,
) : PlayerRepository {
    private val lock = ReentrantLock()
    private val cache = mutableMapOf<PlayerId, PlayerRecord>()
    private val dirtyVersions = mutableMapOf<PlayerId, Long>()
    private val versions = mutableMapOf<PlayerId, Long>()

    private data class PendingSave(
        val id: PlayerId,
        val version: Long,
        val record: PlayerRecord,
    )

    override suspend fun findByName(name: String): PlayerRecord? {
        val cached = lock.withLock { cache.values.find { it.name.equals(name, ignoreCase = true) } }
        if (cached != null) return cached
        val loaded = delegate.findByName(name) ?: return null
        lock.withLock {
            if (cache[loaded.id] == null) {
                cache[loaded.id] = loaded
            }
        }
        return loaded
    }

    override suspend fun findById(id: PlayerId): PlayerRecord? {
        val cached = lock.withLock { cache[id] }
        if (cached != null) return cached
        val loaded = delegate.findById(id) ?: return null
        lock.withLock {
            if (cache[loaded.id] == null) {
                cache[loaded.id] = loaded
            }
        }
        return loaded
    }

    override suspend fun create(
        name: String,
        startRoomId: RoomId,
        nowEpochMs: Long,
        passwordHash: String,
        ansiEnabled: Boolean,
        playerClass: PlayerClass,
        playerRace: PlayerRace,
    ): PlayerRecord {
        val record = delegate.create(name, startRoomId, nowEpochMs, passwordHash, ansiEnabled, playerClass, playerRace)
        lock.withLock {
            cache[record.id] = record
            if (versions[record.id] == null) {
                versions[record.id] = 0L
            }
        }
        return record
    }

    override suspend fun save(record: PlayerRecord) {
        lock.withLock {
            cache[record.id] = record
            val nextVersion = (versions[record.id] ?: 0L) + 1L
            versions[record.id] = nextVersion
            dirtyVersions[record.id] = nextVersion
        }
    }

    suspend fun flushDirty(): Int {
        val toFlush = snapshotDirty()
        if (toFlush.isEmpty()) return 0
        var flushed = 0
        for (pending in toFlush) {
            try {
                delegate.save(pending.record)
                lock.withLock {
                    if (dirtyVersions[pending.id] == pending.version) {
                        dirtyVersions.remove(pending.id)
                    }
                }
                flushed++
            } catch (e: Exception) {
                log.error(e) { "Failed to flush player record: id=${pending.id} name=${pending.record.name}" }
            }
        }
        return flushed
    }

    suspend fun flushAll(): Int {
        var total = 0
        while (true) {
            val flushed = flushDirty()
            total += flushed
            val hasDirty = lock.withLock { dirtyVersions.isNotEmpty() }
            if (!hasDirty || flushed == 0) return total
        }
    }

    fun dirtyCount(): Int = lock.withLock { dirtyVersions.size }

    fun cachedCount(): Int = lock.withLock { cache.size }

    private fun snapshotDirty(): List<PendingSave> =
        lock.withLock {
            dirtyVersions.mapNotNull { (id, version) ->
                val record = cache[id] ?: return@mapNotNull null
                PendingSave(id = id, version = version, record = record)
            }
        }
}
