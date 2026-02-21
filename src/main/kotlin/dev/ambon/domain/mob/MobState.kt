package dev.ambon.domain.mob

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId

data class MobState(
    val id: MobId,
    var name: String,
    var roomId: RoomId,
    var hp: Int = 10,
    var maxHp: Int = 10,
    val minDamage: Int = 1,
    val maxDamage: Int = 4,
    val armor: Int = 0,
    val xpReward: Long = 30L,
)
