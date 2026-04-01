package dev.ambon.engine.dungeon

import dev.ambon.domain.dungeon.DungeonDifficulty
import dev.ambon.domain.dungeon.DungeonTemplateDef
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId

/** An active dungeon run bound to a party. */
data class DungeonInstance(
    val id: String,
    val template: DungeonTemplateDef,
    val difficulty: DungeonDifficulty,
    val layout: DungeonLayout,
    val partyLeader: SessionId,
    /** Session IDs of all party members who entered. */
    val members: MutableSet<SessionId>,
    /** Average party level when the dungeon was created (used for mob scaling). */
    val partyLevel: Int,
    /** Map from layout room index to actual RoomId in the World. */
    val roomIds: Map<Int, RoomId>,
    /** Mob IDs spawned in this instance (for cleanup). */
    val spawnedMobs: MutableSet<MobId> = mutableSetOf(),
    /** The room players return to when leaving or completing. */
    val returnRoom: RoomId,
    /** Whether the boss has been killed and the dungeon is complete. */
    var completed: Boolean = false,
)
