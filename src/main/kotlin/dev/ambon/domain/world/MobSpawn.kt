package dev.ambon.domain.world

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId

data class MobSpawn(
    val id: MobId,
    val name: String,
    val roomId: RoomId,
    val maxHp: Int = 10,
    val minDamage: Int = 1,
    val maxDamage: Int = 4,
    val armor: Int = 0,
    val xpReward: Long = 30L,
    val drops: List<MobDrop> = emptyList(),
)
