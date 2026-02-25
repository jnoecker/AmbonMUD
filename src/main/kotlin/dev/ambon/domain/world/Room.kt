package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId

data class Room(
    val id: RoomId,
    val title: String,
    val description: String,
    val exits: Map<Direction, RoomId>,
    /** Directions whose targets are in zones not loaded on this engine (cross-zone stubs). */
    val remoteExits: Set<Direction> = emptySet(),
    /** Stateful features in this room: doors, containers, levers, signs. */
    val features: List<RoomFeature> = emptyList(),
)
