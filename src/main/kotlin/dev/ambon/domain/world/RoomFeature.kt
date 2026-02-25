package dev.ambon.domain.world

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId

/**
 * Immutable definition of a stateful room feature.
 *
 * Feature IDs follow the pattern `<zone>:<room>/<localId>`, e.g.:
 * - Door at direction NORTH in room `zone:room` → `"zone:room/north"`
 * - Container with local id `old_chest` in room `zone:room` → `"zone:room/old_chest"`
 */
sealed class RoomFeature {
    abstract val id: String
    abstract val roomId: RoomId
    abstract val displayName: String
    abstract val keyword: String

    data class Door(
        override val id: String,
        override val roomId: RoomId,
        override val displayName: String,
        override val keyword: String,
        val direction: Direction,
        val initialState: DoorState,
        val keyItemId: ItemId?,
        val keyConsumed: Boolean,
        val resetWithZone: Boolean,
    ) : RoomFeature()

    data class Container(
        override val id: String,
        override val roomId: RoomId,
        override val displayName: String,
        override val keyword: String,
        val initialState: ContainerState,
        val keyItemId: ItemId?,
        val keyConsumed: Boolean,
        val resetWithZone: Boolean,
        val initialItems: List<ItemId>,
    ) : RoomFeature()

    data class Lever(
        override val id: String,
        override val roomId: RoomId,
        override val displayName: String,
        override val keyword: String,
        val initialState: LeverState,
        val resetWithZone: Boolean,
    ) : RoomFeature()

    data class Sign(
        override val id: String,
        override val roomId: RoomId,
        override val displayName: String,
        override val keyword: String,
        val text: String,
    ) : RoomFeature()
}
