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
 * When [instancing] is `true`, multiple engines may claim the same zone.
 * Instance data (including player counts) is stored in Redis hashes keyed
 * by zone, with one field per engine.
 *
 * Redis key scheme (classic, non-instanced):
 * - `zone:owner:<zone>` → JSON [EngineAddress] (TTL = leaseTtlSeconds)
 *
 * Redis key scheme (instanced):
 * - `zone:instances:<zone>` → HASH { engineId → JSON [ZoneInstance] }
 * - `zone:lease:<zone>:<engineId>` → "1" (TTL = leaseTtlSeconds)
 */
class RedisZoneRegistry(
    private val redis: RedisConnectionManager,
    private val mapper: ObjectMapper,
    private val leaseTtlSeconds: Long = 30L,
    private val keyPrefix: String = "zone:owner:",
    private val instancing: Boolean = false,
    private val defaultCapacity: Int = ZoneInstance.DEFAULT_CAPACITY,
) : ZoneRegistry {
    private val instanceHashPrefix = "zone:instances:"
    private val leasePrefix = "zone:lease:"

    override fun ownerOf(zone: String): EngineAddress? {
        if (instancing) {
            return instancesOf(zone).firstOrNull()?.address
        }
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
        if (instancing) {
            claimZonesInstanced(engineId, address, zones)
        } else {
            claimZonesClassic(engineId, address, zones)
        }
    }

    private fun claimZonesClassic(
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

    private fun claimZonesInstanced(
        engineId: String,
        address: EngineAddress,
        zones: Set<String>,
    ) {
        val commands = redis.commands ?: return
        for (zone in zones) {
            val instance =
                ZoneInstance(
                    engineId = engineId,
                    address = address,
                    zone = zone,
                    capacity = defaultCapacity,
                )
            val json = mapper.writeValueAsString(instance)
            commands.hset("$instanceHashPrefix$zone", engineId, json)
            commands.setex("$leasePrefix$zone:$engineId", leaseTtlSeconds, "1")
        }
        log.info { "Engine '$engineId' claimed instanced zones: $zones (TTL=${leaseTtlSeconds}s)" }
    }

    override fun renewLease(engineId: String) {
        val commands = redis.commands ?: return
        if (instancing) {
            renewLeaseInstanced(engineId, commands)
        } else {
            renewLeaseClassic(engineId, commands)
        }
    }

    private fun renewLeaseClassic(
        engineId: String,
        commands: io.lettuce.core.api.sync.RedisCommands<String, String>,
    ) {
        val cursor =
            commands.scan(
                io.lettuce.core.ScanArgs.Builder
                    .matches("$keyPrefix*")
                    .limit(100),
            )
        for (key in cursor.keys) {
            val json = commands.get(key) ?: continue
            val addr =
                try {
                    mapper.readValue<EngineAddress>(json)
                } catch (_: Exception) {
                    continue
                }
            if (addr.engineId == engineId) {
                commands.expire(key, leaseTtlSeconds)
            }
        }
    }

    private fun renewLeaseInstanced(
        engineId: String,
        commands: io.lettuce.core.api.sync.RedisCommands<String, String>,
    ) {
        val cursor =
            commands.scan(
                io.lettuce.core.ScanArgs.Builder
                    .matches("$leasePrefix*:$engineId")
                    .limit(100),
            )
        for (key in cursor.keys) {
            commands.expire(key, leaseTtlSeconds)
        }
    }

    override fun allAssignments(): Map<String, EngineAddress> {
        if (instancing) {
            return allAssignmentsInstanced()
        }
        return allAssignmentsClassic()
    }

    private fun allAssignmentsClassic(): Map<String, EngineAddress> {
        val commands = redis.commands ?: return emptyMap()
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
            val addr =
                try {
                    mapper.readValue<EngineAddress>(json)
                } catch (_: Exception) {
                    continue
                }
            result[zone] = addr
        }
        return result
    }

    private fun allAssignmentsInstanced(): Map<String, EngineAddress> {
        val commands = redis.commands ?: return emptyMap()
        val result = mutableMapOf<String, EngineAddress>()
        val cursor =
            commands.scan(
                io.lettuce.core.ScanArgs.Builder
                    .matches("$instanceHashPrefix*")
                    .limit(1000),
            )
        for (key in cursor.keys) {
            val zone = key.removePrefix(instanceHashPrefix)
            val entries = commands.hgetall(key) ?: continue
            for ((engineId, json) in entries) {
                // Only include if lease is still alive
                val leaseKey = "$leasePrefix$zone:$engineId"
                if (commands.exists(leaseKey) > 0) {
                    val instance =
                        try {
                            mapper.readValue<ZoneInstance>(json)
                        } catch (_: Exception) {
                            continue
                        }
                    // First live instance wins for allAssignments (1:1 compat)
                    result.putIfAbsent(zone, instance.address)
                }
            }
        }
        return result
    }

    override fun instancesOf(zone: String): List<ZoneInstance> {
        if (!instancing) {
            return listOfNotNull(
                ownerOf(zone)?.let {
                    ZoneInstance(engineId = it.engineId, address = it, zone = zone)
                },
            )
        }
        val commands = redis.commands ?: return emptyList()
        val entries = commands.hgetall("$instanceHashPrefix$zone") ?: return emptyList()
        return entries.mapNotNull { (engineId, json) ->
            val leaseKey = "$leasePrefix$zone:$engineId"
            if (commands.exists(leaseKey) <= 0) return@mapNotNull null
            try {
                mapper.readValue<ZoneInstance>(json)
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun reportLoad(
        engineId: String,
        zoneCounts: Map<String, Int>,
    ) {
        if (!instancing) return
        val commands = redis.commands ?: return
        for ((zone, count) in zoneCounts) {
            val hashKey = "$instanceHashPrefix$zone"
            val existingJson = commands.hget(hashKey, engineId) ?: continue
            val instance =
                try {
                    mapper.readValue<ZoneInstance>(existingJson)
                } catch (_: Exception) {
                    continue
                }
            val updated = instance.copy(playerCount = count)
            commands.hset(hashKey, engineId, mapper.writeValueAsString(updated))
        }
    }

    override fun instancingEnabled(): Boolean = instancing
}
