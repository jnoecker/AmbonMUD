package dev.ambon.domain.world.data

/**
 * One placement entry for a mob template. The loader expands a [MobSpawnFile]
 * into [count] runtime [dev.ambon.domain.world.MobSpawn] records, all sharing
 * the same template stats but each with a distinct instance id.
 */
data class MobSpawnFile(
    val room: String,
    val count: Int = 1,
)
