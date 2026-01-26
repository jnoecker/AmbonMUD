package dev.ambon.domain.world

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.ItemInstance

data class ItemSpawn(
    val instance: ItemInstance,
    val roomId: RoomId? = null,
    val mobId: MobId? = null,
)
