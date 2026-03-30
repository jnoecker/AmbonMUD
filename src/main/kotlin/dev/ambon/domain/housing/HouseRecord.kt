package dev.ambon.domain.housing

import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.Direction
import dev.ambon.persistence.PlayerId

/**
 * Persisted representation of a player's house.
 *
 * Each player may own at most one house. The house contains one or more rooms,
 * each instantiated from a [RoomTemplate]. Rooms are connected to each other
 * via player-chosen directions; the entry room (index 0) additionally has a
 * dynamic exit back to the world.
 */
data class HouseRecord(
    val ownerId: PlayerId,
    val ownerName: String,
    val rooms: List<HouseRoomRecord>,
    val createdAtEpochMs: Long,
)

/**
 * A single room within a player's house.
 *
 * @property templateId references a [RoomTemplate] by id.
 * @property customTitle player-set title override (null = use template default).
 * @property customDescription player-set description override (null = use template default).
 * @property exits connections to other rooms in the same house, keyed by direction,
 *           valued by the target room's index in [HouseRecord.rooms].
 * @property storedItems items persisted in this room (only meaningful for vault-type rooms).
 */
data class HouseRoomRecord(
    val templateId: String,
    val customTitle: String? = null,
    val customDescription: String? = null,
    val exits: Map<Direction, Int> = emptyMap(),
    val storedItems: List<ItemInstance> = emptyList(),
)
