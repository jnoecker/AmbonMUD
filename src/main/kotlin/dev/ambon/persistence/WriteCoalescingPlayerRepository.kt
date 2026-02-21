package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId
import io.github.oshai.kotlinlogging.KotlinLogging

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
    private val cache = mutableMapOf<PlayerId, PlayerRecord>()
    private val dirty = mutableSetOf<PlayerId>()

    override suspend fun findByName(name: String): PlayerRecord? {
        val cached = cache.values.find { it.name.equals(name, ignoreCase = true) }
        if (cached != null) return cached
        return delegate.findByName(name)?.also { cache[it.id] = it }
    }

    override suspend fun findById(id: PlayerId): PlayerRecord? {
        val cached = cache[id]
        if (cached != null) return cached
        return delegate.findById(id)?.also { cache[it.id] = it }
    }

    override suspend fun create(
        name: String,
        startRoomId: RoomId,
        nowEpochMs: Long,
        passwordHash: String,
        ansiEnabled: Boolean,
    ): PlayerRecord {
        val record = delegate.create(name, startRoomId, nowEpochMs, passwordHash, ansiEnabled)
        cache[record.id] = record
        return record
    }

    override suspend fun save(record: PlayerRecord) {
        cache[record.id] = record
        dirty.add(record.id)
    }

    suspend fun flushDirty(): Int {
        if (dirty.isEmpty()) return 0
        val toFlush = dirty.toList()
        var flushed = 0
        for (id in toFlush) {
            val record = cache[id] ?: continue
            try {
                delegate.save(record)
                dirty.remove(id)
                flushed++
            } catch (e: Exception) {
                log.error(e) { "Failed to flush player record: id=$id name=${record.name}" }
            }
        }
        return flushed
    }

    suspend fun flushAll(): Int = flushDirty()

    fun dirtyCount(): Int = dirty.size

    fun cachedCount(): Int = cache.size
}
