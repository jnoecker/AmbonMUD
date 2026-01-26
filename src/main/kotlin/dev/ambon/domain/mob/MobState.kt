package dev.ambon.domain.mob

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId

data class MobState(
    val id: MobId,
    var name: String,
    var roomId: RoomId,
)
