package dev.ambon.engine.dungeon

import dev.ambon.domain.dungeon.DungeonRoomType
import dev.ambon.domain.world.Direction

/** A single room in a generated dungeon layout. */
data class DungeonLayoutRoom(
    val index: Int,
    val type: DungeonRoomType,
    val title: String,
    val description: String,
    val image: String?,
    val exits: Map<Direction, Int>,
    /** Mob template IDs to spawn in this room. */
    val mobIds: List<String>,
)

/** A generated dungeon layout ready for instantiation. */
data class DungeonLayout(
    val rooms: List<DungeonLayoutRoom>,
    val entranceIndex: Int = 0,
    val bossIndex: Int,
)
