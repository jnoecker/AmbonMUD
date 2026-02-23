package dev.ambon.sharding

import dev.ambon.redis.RedisConnectionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ScriptOutputType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * Redis-backed player location index.
 *
 * Keys: `player:online:<lowercase_name>` → engineId (string)
 * TTL:  [keyTtlSeconds] — refreshed by periodic call to [refreshTtls]
 *
 * Write operations (register, unregister, refreshTtls) use the Lettuce async
 * API so they are fire-and-forget and never block the caller's thread.
 *
 * [lookupEngineId] dispatches to [Dispatchers.IO] so it never blocks the
 * engine's single-threaded coroutine dispatcher.
 *
 * [unregister] uses a Lua compare-and-delete script so it only removes the
 * key when this engine is still the recorded owner, preventing accidental
 * deletion of a registration written by the target engine during a handoff.
 */
class RedisPlayerLocationIndex(
    private val engineId: String,
    private val redis: RedisConnectionManager,
    private val keyTtlSeconds: Long,
) : PlayerLocationIndex {
    // Thread-safe set of lowercase names registered on this engine.
    // Maintained locally so refreshTtls() never needs to read PlayerRegistry.
    private val registeredNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Atomically deletes the key only when the stored value equals our engineId.
    private val conditionalDeleteScript =
        "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end"

    private fun key(playerName: String): String = "player:online:${playerName.lowercase()}"

    override fun register(playerName: String) {
        val lower = playerName.lowercase()
        try {
            // asyncCommands returns immediately without blocking.
            redis.asyncCommands?.setex(key(playerName), keyTtlSeconds, engineId)
            registeredNames.add(lower)
        } catch (e: Exception) {
            log.warn(e) { "PlayerLocationIndex.register failed for player=$playerName" }
        }
    }

    override fun unregister(playerName: String) {
        val lower = playerName.lowercase()
        try {
            // Conditional delete: only removes the key when this engine is the current owner.
            // Prevents overwriting a registration written by the target engine during handoff.
            redis.asyncCommands?.eval<Long>(
                conditionalDeleteScript,
                ScriptOutputType.INTEGER,
                arrayOf(key(playerName)),
                engineId,
            )
            registeredNames.remove(lower)
        } catch (e: Exception) {
            log.warn(e) { "PlayerLocationIndex.unregister failed for player=$playerName" }
        }
    }

    override suspend fun lookupEngineId(playerName: String): String? =
        withContext(Dispatchers.IO) {
            try {
                redis.commands?.get(key(playerName))
            } catch (e: Exception) {
                log.warn(e) { "PlayerLocationIndex.lookup failed for player=$playerName" }
                null
            }
        }

    override fun refreshTtls() {
        if (registeredNames.isEmpty()) return
        try {
            val cmds = redis.asyncCommands ?: return
            for (name in registeredNames) {
                cmds.expire(key(name), keyTtlSeconds)
            }
        } catch (e: Exception) {
            log.warn(e) { "PlayerLocationIndex.refreshTtls failed" }
        }
    }
}
