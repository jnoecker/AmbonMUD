package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.World

/**
 * Boat docks — room kiosks that let players pay gold to sail a fixed set of author-defined routes.
 *
 * Unlike the flight master (a per-player, discovery-gated network priced by travel distance), a boat
 * dock exposes exactly the routes the worldbuilder authored on it: each [dev.ambon.domain.world.BoatRoute]
 * is a flat, author-set fare to a destination, available immediately — no exploration required, and the
 * fare is paid on every trip. The dock's `boatRoutes` list is the single source of truth; there is no
 * discovery state to record.
 *
 * This system owns the read-side logic (is-a-dock, route listing); the gold charge + teleport live in
 * `BoatHandler`.
 */
class BoatSystem(
    private val world: World,
) {
    /** A purchasable route leaving the player's current dock. */
    data class Destination(
        val roomId: RoomId,
        val name: String,
        val zone: String,
        /** Flat author-set fare in gold. */
        val price: Long,
        /** World-map pin as a percentage (0–100) of the Ambon map, or null when the destination is unpinned. */
        val mapX: Double?,
        val mapY: Double?,
    )

    /** The dock the player is standing at: its name + world-map pin, for the "you are here" marker. */
    data class Origin(
        val roomId: RoomId,
        val name: String,
        val mapX: Double?,
        val mapY: Double?,
    )

    /** True if [roomId] is a boat dock right now. */
    fun isBoatDock(roomId: RoomId): Boolean = world.rooms[roomId]?.boatDock == true

    /** The origin marker for [roomId] when it is a boat dock, else null. */
    fun originAt(roomId: RoomId): Origin? {
        val room = world.rooms[roomId] ?: return null
        if (!room.boatDock) return null
        return Origin(roomId = roomId, name = room.title, mapX = room.boatMapX, mapY = room.boatMapY)
    }

    /**
     * Routes the player can currently sail from [origin]: every authored route whose destination room
     * still exists on this engine, in authored order. Each destination's map pin is read from the
     * destination room's own `boatMapX`/`boatMapY` (null when the destination isn't pinned).
     */
    fun destinationsFrom(origin: RoomId): List<Destination> {
        val room = world.rooms[origin] ?: return emptyList()
        if (!room.boatDock) return emptyList()
        return room.boatRoutes.mapNotNull { route ->
            val dest = world.rooms[route.to] ?: return@mapNotNull null
            if (dest.id == origin) return@mapNotNull null
            Destination(
                roomId = dest.id,
                name = dest.title,
                zone = dest.id.zone,
                price = route.price,
                mapX = dest.boatMapX,
                mapY = dest.boatMapY,
            )
        }
    }
}
