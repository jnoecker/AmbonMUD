package dev.ambon.sharding

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.redis.RedisConnectionManager
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Zone registry backed by Redis keys with TTL. Each engine writes its zone
 * claims as Redis keys and refreshes them periodically. If an engine dies,
 * its leases expire after [leaseTtlSeconds].
 *
 * Redis key scheme:
 * - `zone:owner:<zone>` → JSON [EngineAddress] (TTL = leaseTtlSeconds)
 */
class RedisZoneRegistry(
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

    override fun claimZones(engineId: String, address: EngineAddress, zones: Set<String>) {
        val json = mapper.writeValueAsString(address)
        for (zone in zones) {
            redis.setEx("$keyPrefix$zone", leaseTtlSeconds, json)
        }
        log.info { "Engine '$engineId' claimed zones: $zones (TTL=${leaseTtlSeconds}s)" }
    }

    override fun renewLease(engineId: String) {
        val commands = redis.commands ?: return
        // Scan for all keys this engine owns and refresh TTL.
        // In practice, the engine knows its own zones; callers should
        // call claimZones again to renew. This is a convenience for
        // the heartbeat loop.
        val cursor = commands.scan(io.lettuce.core.ScanArgs.Builder.matches("$keyPrefix*").limit(100))
        for (key in cursor.keys) {
            val json = commands.get(key) ?: continue
            val addr = try {
                mapper.readValue<EngineAddress>(json)
            } catch (_: Exception) {
                continue
            }
            if (addr.engineId == engineId) {
                commands.expire(key, leaseTtlSeconds)
            }
        }
    }

    override fun allAssignments(): Map<String, EngineAddress> {
        val commands = redis.commands ?: return emptyMap()
        val result = mutableMapOf<String, EngineAddress>()
        val cursor = commands.scan(io.lettuce.core.ScanArgs.Builder.matches("$keyPrefix*").limit(1000))
        for (key in cursor.keys) {
            val zone = key.removePrefix(keyPrefix)
            val json = commands.get(key) ?: continue
            val addr = try {
                mapper.readValue<EngineAddress>(json)
            } catch (_: Exception) {
                continue
            }
            result[zone] = addr
        }
        return result
    }
}
