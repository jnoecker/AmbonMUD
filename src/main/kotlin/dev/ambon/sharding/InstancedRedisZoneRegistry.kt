package dev.ambon.sharding

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ambon.redis.RedisConnectionManager
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Zone registry backed by Redis hashes with per-engine lease keys, instanced mode.
 * Multiple engines may claim the same zone; each (zone, engineId) pair is a
 * distinct [ZoneInstance] with its own player count and capacity.
 *
 * Redis key scheme:
 * - `zone:instances:<zone>` → HASH { engineId → JSON [ZoneInstance] }
 * - `zone:lease:<zone>:<engineId>` → "1" (TTL = leaseTtlSeconds)
 */
class InstancedRedisZoneRegistry(
    private val redis: RedisConnectionManager,
    private val mapper: ObjectMapper,
    private val leaseTtlSeconds: Long = 30L,
    private val defaultCapacity: Int = ZoneInstance.DEFAULT_CAPACITY,
) : ZoneRegistry {
    private val instanceHashPrefix = "zone:instances:"
    private val leasePrefix = "zone:lease:"

    override fun ownerOf(zone: String): EngineAddress? =
        instancesOf(zone).firstOrNull()?.address

    override fun claimZones(
        engineId: String,
        address: EngineAddress,
        zones: Set<String>,
    ) {
        redis.withCommands { commands ->
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
    }

    override fun renewLease(engineId: String) {
        redis.withCommands { commands ->
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
    }

    override fun allAssignments(): Map<String, EngineAddress> =
        redis.withCommands { commands ->
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
                for ((engineId, json) in entries.toSortedMap()) {
                    val leaseKey = "$leasePrefix$zone:$engineId"
                    if (commands.exists(leaseKey) > 0) {
                        val instance = mapper.readValueOrNull<ZoneInstance>(json) ?: continue
                        result.putIfAbsent(zone, instance.address)
                    }
                }
            }
            result
        } ?: emptyMap()

    override fun instancesOf(zone: String): List<ZoneInstance> =
        redis.withCommands { commands ->
            val entries = commands.hgetall("$instanceHashPrefix$zone") ?: return@withCommands emptyList()
            entries
                .mapNotNull { (engineId, json) ->
                    val leaseKey = "$leasePrefix$zone:$engineId"
                    if (commands.exists(leaseKey) <= 0) return@mapNotNull null
                    mapper.readValueOrNull<ZoneInstance>(json)
                }.sortedBy { it.engineId }
        } ?: emptyList()

    override fun reportLoad(
        engineId: String,
        zoneCounts: Map<String, Int>,
    ) {
        redis.withCommands { commands ->
            for ((zone, count) in zoneCounts) {
                val hashKey = "$instanceHashPrefix$zone"
                val existingJson = commands.hget(hashKey, engineId) ?: continue
                val instance = mapper.readValueOrNull<ZoneInstance>(existingJson) ?: continue
                val updated = instance.copy(playerCount = count)
                commands.hset(hashKey, engineId, mapper.writeValueAsString(updated))
            }
        }
    }

    override fun instancingEnabled(): Boolean = true
}
