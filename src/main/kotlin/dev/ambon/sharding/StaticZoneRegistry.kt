package dev.ambon.sharding

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Zone registry backed by static configuration. Zone assignments are fixed at
 * startup and never change. Suitable for development or simple two-engine setups.
 *
 * @param assignments Map of engineId to (address, zones) pairs. Built from config.
 */
class StaticZoneRegistry(
    assignments: Map<String, Pair<EngineAddress, Set<String>>>,
) : ZoneRegistry {
    private val zoneToEngine: Map<String, EngineAddress>

    init {
        val map = mutableMapOf<String, EngineAddress>()
        for ((_, pair) in assignments) {
            val (address, zones) = pair
            for (zone in zones) {
                require(zone !in map) { "Zone '$zone' assigned to multiple engines" }
                map[zone] = address
            }
        }
        zoneToEngine = map
        log.info { "StaticZoneRegistry initialized: ${zoneToEngine.mapValues { it.value.engineId }}" }
    }

    override fun ownerOf(zone: String): EngineAddress? = zoneToEngine[zone]

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

    override fun allAssignments(): Map<String, EngineAddress> = zoneToEngine
}
