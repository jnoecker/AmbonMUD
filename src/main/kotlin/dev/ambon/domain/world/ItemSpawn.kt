package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.ItemInstance

data class ItemSpawn(
    val instance: ItemInstance,
    val roomId: RoomId? = null,
    /**
     * Seconds after the instance leaves its spawn room before it is re-placed.
     * Null = the item only returns on zone reset (the historical behavior).
     * Only valid for room-placed spawns.
     */
    val respawnSeconds: Long? = null,
)
