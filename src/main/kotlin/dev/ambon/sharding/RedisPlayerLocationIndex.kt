package dev.ambon.sharding

import dev.ambon.redis.RedisConnectionManager
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Redis-backed player location index.
 *
 * Keys: `player:online:<lowercase_name>` → engineId (string)
 * TTL:  [keyTtlSeconds] — refreshed by periodic heartbeat
 */
class RedisPlayerLocationIndex(
    private val engineId: String,
    private val redis: RedisConnectionManager,
    private val keyTtlSeconds: Long,
) : PlayerLocationIndex {
    private fun key(playerName: String): String = "player:online:${playerName.lowercase()}"

    override fun register(playerName: String) {
        try {
            redis.commands?.setex(key(playerName), keyTtlSeconds, engineId)
        } catch (e: Exception) {
            log.warn(e) { "PlayerLocationIndex.register failed for player=$playerName" }
        }
    }

    override fun unregister(playerName: String) {
        try {
            redis.commands?.del(key(playerName))
        } catch (e: Exception) {
            log.warn(e) { "PlayerLocationIndex.unregister failed for player=$playerName" }
        }
    }

    override fun lookupEngineId(playerName: String): String? =
        try {
            redis.commands?.get(key(playerName))
        } catch (e: Exception) {
            log.warn(e) { "PlayerLocationIndex.lookup failed for player=$playerName" }
            null
        }

    override fun refreshAll(playerNames: Collection<String>) {
        if (playerNames.isEmpty()) return
        try {
            for (name in playerNames) {
                redis.commands?.expire(key(name), keyTtlSeconds)
            }
        } catch (e: Exception) {
            log.warn(e) { "PlayerLocationIndex.refreshAll failed" }
        }
    }
}
