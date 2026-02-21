package dev.ambon.redis

import dev.ambon.config.RedisConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands

private val log = KotlinLogging.logger {}

class RedisConnectionManager(
    private val config: RedisConfig,
) : AutoCloseable {
    var commands: RedisCommands<String, String>? = null
        private set

    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null

    fun connect() {
        try {
            val redisClient = RedisClient.create(config.uri)
            val conn = redisClient.connect()
            conn.sync().ping()
            client = redisClient
            connection = conn
            commands = conn.sync()
            log.info { "Redis connection established (uri=${config.uri})" }
        } catch (e: Exception) {
            log.warn(e) { "Redis connection failed — operating without Redis cache (uri=${config.uri})" }
            commands = null
        }
    }

    override fun close() {
        runCatching { connection?.close() }
        runCatching { client?.shutdown() }
        commands = null
        log.info { "Redis connection closed" }
    }
}
