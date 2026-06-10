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

/**
 * Strips any `user:password@` userinfo from a Redis URI so the AUTH password is never written to
 * logs. Production Redis credentials are commonly supplied inline as `redis://:secret@host:6379`.
 * Returns the input unchanged if it carries no userinfo or cannot be parsed.
 */
internal fun redactRedisUri(uri: String): String {
    val schemeIdx = uri.indexOf("://")
    if (schemeIdx < 0) return uri
    val afterScheme = schemeIdx + 3
    // Userinfo lives in the authority component (between "://" and the next '/'). Split on the LAST
    // '@' inside it so an unencoded '@' in the password is redacted too.
    val authorityEnd = uri.indexOf('/', afterScheme).let { if (it < 0) uri.length else it }
    val atIdx = uri.lastIndexOf('@', authorityEnd - 1)
    if (atIdx < afterScheme) return uri
    return uri.substring(0, afterScheme) + "***@" + uri.substring(atIdx + 1)
}

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
            log.info { "Redis connection established (uri=${redactRedisUri(config.uri)})" }
        } catch (e: Exception) {
            log.warn(e) { "Redis connection failed - operating without Redis cache (uri=${redactRedisUri(config.uri)})" }
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
     * Returns `true` when a Redis connection has been successfully established
     * and neither [close] nor a reconnection failure has cleared the command handles.
     */
    fun isConnected(): Boolean = commands != null

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
