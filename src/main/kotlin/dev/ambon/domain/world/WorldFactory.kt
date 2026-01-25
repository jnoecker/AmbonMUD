package dev.ambon.domain.world

import dev.ambon.domain.world.load.WorldLoader

object WorldFactory {
    fun demoWorld(): World =
        WorldLoader.loadFromResources(
            listOf(
                "world/enchanted_forest.yaml",
                "world/swamp.yaml",
            ),
        )
}
