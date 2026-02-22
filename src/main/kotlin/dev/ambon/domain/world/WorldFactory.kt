package dev.ambon.domain.world

import dev.ambon.config.MobTiersConfig
import dev.ambon.domain.world.load.WorldLoader

object WorldFactory {
    private val defaultWorldResources = listOf("world/ambon_hub.yaml", "world/noecker_resume.yaml", "world/demo_ruins.yaml")

    fun demoWorld(
        resources: List<String> = defaultWorldResources,
        tiers: MobTiersConfig = MobTiersConfig(),
        zoneFilter: Set<String> = emptySet(),
    ): World = WorldLoader.loadFromResources(resources, tiers, zoneFilter)
}
