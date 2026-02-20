package dev.ambon.domain.world

import dev.ambon.domain.world.load.WorldLoader

object WorldFactory {
    private val defaultWorldResources = listOf("world/demo_ruins.yaml")

    fun demoWorld(resources: List<String> = defaultWorldResources): World = WorldLoader.loadFromResources(resources)
}
