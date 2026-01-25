package dev.ambon.domain.world

import dev.ambon.domain.world.load.WorldLoader

object WorldFactory {
    fun demoWorld(): World = WorldLoader.loadFromResource("world/demo.yaml")
}
