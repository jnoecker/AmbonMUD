package dev.ambon.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.redis.redisObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val log = KotlinLogging.logger {}

class RedisCachingPlayerRepository(
    private val delegate: PlayerRepository,
    private val cache: StringCache,
    private val cacheTtlSeconds: Long,
    private val mapper: ObjectMapper = redisObjectMapper,
) : PlayerRepository {
    private fun nameKey(name: String) = "player:name:${name.lowercase()}"

    private fun idKey(id: Long) = "player:id:$id"

    override suspend fun findByName(name: String): PlayerRecord? =
        cachedLookup(
            warningContext = "findByName($name)",
            fromCache = {
                val idStr = cache.get(nameKey(name)) ?: return@cachedLookup null
                val id = idStr.toLongOrNull() ?: return@cachedLookup null
                val json = cache.get(idKey(id)) ?: return@cachedLookup null
                mapper.readValue<PlayerDto>(json).toDomain()
            },
            fromDelegate = { delegate.findByName(name) },
        )

    override suspend fun findById(id: PlayerId): PlayerRecord? =
        cachedLookup(
            warningContext = "findById($id)",
            fromCache = {
                val json = cache.get(idKey(id.value)) ?: return@cachedLookup null
                mapper.readValue<PlayerDto>(json).toDomain()
            },
            fromDelegate = { delegate.findById(id) },
        )

    override suspend fun create(request: PlayerCreationRequest): PlayerRecord {
        val record = delegate.create(request)
        cacheRecord(record)
        return record
    }

    override suspend fun save(record: PlayerRecord) {
        delegate.save(record)
        cacheRecord(record)
    }

    private suspend fun cachedLookup(
        warningContext: String,
        fromCache: suspend () -> PlayerRecord?,
        fromDelegate: suspend () -> PlayerRecord?,
    ): PlayerRecord? {
        try {
            val cached =
                withContext(Dispatchers.IO) {
                    fromCache()
                }
            if (cached != null) return cached
        } catch (e: Exception) {
            log.warn(e) { "Redis error in $warningContext - falling through to delegate" }
        }
        val record = fromDelegate()
        if (record != null) cacheRecord(record)
        return record
    }

    private suspend fun cacheRecord(record: PlayerRecord) {
        try {
            withContext(Dispatchers.IO) {
                val json = mapper.writeValueAsString(PlayerDto.from(record))
                cache.setEx(idKey(record.id.value), cacheTtlSeconds, json)
                cache.setEx(nameKey(record.name), cacheTtlSeconds, record.id.value.toString())
            }
        } catch (e: Exception) {
            log.warn(e) { "Redis error caching player ${record.name} - ignoring" }
        }
    }
}
