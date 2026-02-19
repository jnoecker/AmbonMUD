package dev.ambon.domain.world

import dev.ambon.domain.world.load.WorldLoader

object WorldFactory {
    fun demoWorld(): World =
        WorldLoader.loadFromResources(
            listOf(
                "world/demo_ruins.yaml",
            ),
        )
}
