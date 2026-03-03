package dev.ambon.sharding

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.redis.RedisConnectionManager
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Zone registry backed by Redis keys with TTL, classic (non-instanced) mode.
 * Each zone maps to exactly one engine via a simple key-value pair.
 *
 * Redis key scheme:
 * - `zone:owner:<zone>` → JSON [EngineAddress] (TTL = leaseTtlSeconds)
 */
class ClassicRedisZoneRegistry(
    private val redis: RedisConnectionManager,
    private val mapper: ObjectMapper,
    private val leaseTtlSeconds: Long = 30L,
    private val keyPrefix: String = "zone:owner:",
) : ZoneRegistry {
    override fun ownerOf(zone: String): EngineAddress? {
        val json = redis.get("$keyPrefix$zone") ?: return null
        return try {
            mapper.readValue<EngineAddress>(json)
        } catch (e: Exception) {
            log.warn(e) { "Failed to deserialize zone owner for '$zone'" }
            null
        }
    }

    override fun claimZones(
        engineId: String,
        address: EngineAddress,
        zones: Set<String>,
    ) {
        val json = mapper.writeValueAsString(address)
        for (zone in zones) {
            redis.setEx("$keyPrefix$zone", leaseTtlSeconds, json)
        }
        log.info { "Engine '$engineId' claimed zones: $zones (TTL=${leaseTtlSeconds}s)" }
    }

    override fun renewLease(engineId: String) {
        redis.withCommands { commands ->
            val cursor =
                commands.scan(
                    io.lettuce.core.ScanArgs.Builder
                        .matches("$keyPrefix*")
                        .limit(100),
                )
            for (key in cursor.keys) {
                val json = commands.get(key) ?: continue
                val addr = mapper.readValueOrNull<EngineAddress>(json) ?: continue
                if (addr.engineId == engineId) {
                    commands.expire(key, leaseTtlSeconds)
                }
            }
        }
    }

    override fun allAssignments(): Map<String, EngineAddress> =
        redis.withCommands { commands ->
            val result = mutableMapOf<String, EngineAddress>()
            val cursor =
                commands.scan(
                    io.lettuce.core.ScanArgs.Builder
                        .matches("$keyPrefix*")
                        .limit(1000),
                )
            for (key in cursor.keys) {
                val zone = key.removePrefix(keyPrefix)
                val json = commands.get(key) ?: continue
                val addr = mapper.readValueOrNull<EngineAddress>(json) ?: continue
                result[zone] = addr
            }
            result
        } ?: emptyMap()
}
