package dev.ambon.redis

import dev.ambon.config.RedisConfig
import dev.ambon.persistence.StringCache
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

private val log = KotlinLogging.logger {}

class RedisConnectionManager(
    private val config: RedisConfig,
) : AutoCloseable,
    StringCache {
    var commands: RedisCommands<String, String>? = null
        private set

    var asyncCommands: RedisAsyncCommands<String, String>? = null
        private set

    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null
    private val pubSubConnections = mutableListOf<StatefulRedisPubSubConnection<String, String>>()

    fun connect() {
        try {
            val redisClient = RedisClient.create(config.uri)
            val conn = redisClient.connect()
            conn.sync().ping()
            client = redisClient
            connection = conn
            commands = conn.sync()
            asyncCommands = conn.async()
            log.info { "Redis connection established (uri=${config.uri})" }
        } catch (e: Exception) {
            log.warn(e) { "Redis connection failed - operating without Redis cache (uri=${config.uri})" }
            commands = null
            asyncCommands = null
        }
    }

    fun connectPubSub(): StatefulRedisPubSubConnection<String, String>? {
        val c = client ?: return null
        return try {
            c.connectPubSub().also { pubSubConnections.add(it) }
        } catch (e: Exception) {
            log.warn(e) { "Failed to create Redis pub/sub connection" }
            null
        }
    }

    override fun get(key: String): String? = commands?.get(key)

    override fun setEx(
        key: String,
        ttlSeconds: Long,
        value: String,
    ) {
        commands?.setex(key, ttlSeconds, value)
    }

    /**
     * Executes [block] with the sync [RedisCommands] handle if Redis is connected,
     * returning the block's result.  Returns `null` when the connection is absent.
     *
     * Callers that need a non-null default can chain the Elvis operator:
     * ```
     * redis.withCommands { it.hgetall(key) } ?: emptyMap()
     * ```
     */
    inline fun <T> withCommands(block: (RedisCommands<String, String>) -> T): T? =
        commands?.let(block)

    /**
     * Executes [block] with the async [RedisAsyncCommands] handle if Redis is
     * connected, returning the block's result.  Returns `null` when absent.
     */
    inline fun <T> withAsyncCommands(block: (RedisAsyncCommands<String, String>) -> T): T? =
        asyncCommands?.let(block)

    override fun close() {
        pubSubConnections.forEach { runCatching { it.close() } }
        pubSubConnections.clear()
        runCatching { connection?.close() }
        runCatching { client?.shutdown() }
        commands = null
        asyncCommands = null
        log.info { "Redis connection closed" }
    }
}
