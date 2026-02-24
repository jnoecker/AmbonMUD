package dev.ambon.sharding

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * Zone registry backed by static configuration. Zone assignments are fixed at
 * startup and never change. Suitable for development or simple two-engine setups.
 *
 * When [instancing] is `true`, multiple engines may claim the same zone and each
 * (zone, engineId) pair is treated as a distinct instance. When `false`, duplicate
 * zone assignments are rejected at construction time.
 *
 * @param assignments Map of engineId to (address, zones) pairs. Built from config.
 * @param instancing Whether zone instancing is enabled (allows duplicate zones).
 */
class StaticZoneRegistry(
    assignments: Map<String, Pair<EngineAddress, Set<String>>>,
    private val instancing: Boolean = false,
) : ZoneRegistry {
    private val zoneToInstances: Map<String, List<ZoneInstance>>

    /** Per-instance player counts, updated via [reportLoad]. */
    private val loadCounts = ConcurrentHashMap<String, Int>()

    init {
        val map = mutableMapOf<String, MutableList<ZoneInstance>>()
        val seenZones = mutableSetOf<String>()
        for ((_, pair) in assignments) {
            val (address, zones) = pair
            for (zone in zones) {
                if (!instancing) {
                    require(zone !in seenZones) { "Zone '$zone' assigned to multiple engines" }
                    seenZones.add(zone)
                }
                map.getOrPut(zone) { mutableListOf() }.add(
                    ZoneInstance(
                        engineId = address.engineId,
                        address = address,
                        zone = zone,
                    ),
                )
            }
        }
        zoneToInstances = map
        log.info {
            val summary = zoneToInstances.mapValues { (_, instances) -> instances.map { it.engineId } }
            "StaticZoneRegistry initialized (instancing=$instancing): $summary"
        }
    }

    override fun ownerOf(zone: String): EngineAddress? = zoneToInstances[zone]?.firstOrNull()?.address

    override fun claimZones(
        engineId: String,
        address: EngineAddress,
        zones: Set<String>,
    ) {
        // No-op for static registry; assignments are fixed at construction.
    }

    override fun renewLease(engineId: String) {
        // No-op for static registry; no leases to renew.
    }

    override fun allAssignments(): Map<String, EngineAddress> = zoneToInstances.mapValues { (_, instances) -> instances.first().address }

    override fun instancesOf(zone: String): List<ZoneInstance> =
        zoneToInstances[zone]?.map { instance ->
            val count = loadCounts["${instance.engineId}:${instance.zone}"] ?: 0
            instance.copy(playerCount = count)
        } ?: emptyList()

    override fun reportLoad(
        engineId: String,
        zoneCounts: Map<String, Int>,
    ) {
        for ((zone, count) in zoneCounts) {
            loadCounts["$engineId:$zone"] = count
        }
    }

    override fun instancingEnabled(): Boolean = instancing
}
