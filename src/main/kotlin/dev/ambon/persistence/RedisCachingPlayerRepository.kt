package dev.ambon.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.redis.redisObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val log = KotlinLogging.logger {}

class RedisCachingPlayerRepository(
    delegate: PlayerRepository,
    private val cache: StringCache,
    private val cacheTtlSeconds: Long,
    private val mapper: ObjectMapper = redisObjectMapper,
) : DelegatingPlayerRepository(delegate) {
    private fun nameKey(name: String) = "player:name:${name.lowercase()}"

    private fun idKey(id: Long) = "player:id:$id"

    override suspend fun lookupCachedByName(name: String): PlayerRecord? =
        withCacheReadFallback("findByName($name)") {
            val idStr = cache.get(nameKey(name)) ?: return@withCacheReadFallback null
            val id = idStr.toLongOrNull() ?: return@withCacheReadFallback null
            val json = cache.get(idKey(id)) ?: return@withCacheReadFallback null
            mapper.readValue<PlayerDto>(json).toDomain()
        }

    override suspend fun lookupCachedById(id: PlayerId): PlayerRecord? =
        withCacheReadFallback("findById($id)") {
            val json = cache.get(idKey(id.value)) ?: return@withCacheReadFallback null
            mapper.readValue<PlayerDto>(json).toDomain()
        }

    override suspend fun storeInCache(record: PlayerRecord) {
        cacheRecord(record)
    }

    private suspend fun withCacheReadFallback(
        warningContext: String,
        lookup: suspend () -> PlayerRecord?,
    ): PlayerRecord? {
        try {
            return withContext(Dispatchers.IO) { lookup() }
        } catch (e: Exception) {
            log.warn(e) { "Redis error in $warningContext - falling through to delegate" }
        }
        return null
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
