package dev.ambon.domain.world

import dev.ambon.domain.world.load.WorldLoader

object WorldFactory {
    fun fromResources(paths: List<String>): World = WorldLoader.loadFromResources(paths)

    fun demoWorld(): World =
        fromResources(
            listOf(
                "world/demo_ruins.yaml",
            ),
        )
}
