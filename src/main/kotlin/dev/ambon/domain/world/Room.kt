package dev.ambon.domain.world

import dev.ambon.domain.crafting.CraftingStationType
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
    /** Crafting station available in this room, if any. */
    val station: CraftingStationType? = null,
    /** URL to an image representing this room. */
    val image: String? = null,
    /** URL to a video cinematic for this room. */
    val video: String? = null,
    /** Background music track URL for this room. */
    val music: String? = null,
    /** Ambient sound loop URL for this room. */
    val ambient: String? = null,
)
