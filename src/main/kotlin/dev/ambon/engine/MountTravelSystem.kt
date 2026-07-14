package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.config.MountTravelConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.sprite.SpriteCategory
import dev.ambon.domain.world.World
import dev.ambon.engine.commands.handlers.movePlayerWithNotify
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.metrics.GameMetrics
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Mount fast travel — map click-to-ride.
 *
 * Owning any shop-bought mount ([PlayerState.ownedMounts]) lets a player pick an explored
 * room and auto-ride the shortest *known* path there: a BFS over rooms the player has
 * explored, constrained to the current zone except that the destination itself may sit one
 * exit-hop into an adjacent zone (the border stubs visible on the zone map).
 *
 * The ride is free but deliberately not instant. Each step is a real room-to-room move
 * scheduled every [MountTravelConfig.msPerRoom] ms (divided by the mount's authored speed
 * multiplier) on the engine [Scheduler], so bystanders see the rider pass through, the
 * client map animates from the resulting `Room.Info` stream, and per-room side effects
 * (exploration, flight-point discovery, pet follow) fire naturally via [onPlayerMoved].
 * While riding, [PlayerState.ridingMountId] makes sprite resolution show the mount and
 * aggressive mobs skip the rider.
 *
 * *Flying* mounts (items authored `flying: true`) additionally cover destinations no known
 * ground path reaches — an explored room in any zone, picked from the world map atlas's
 * zone charts. A flight keeps the rider in place for an airborne delay scaled from the two
 * zones' world-map placements, then lands them at the destination in one hop.
 *
 * A ride or flight cancels when combat starts, when the player moves by any other means
 * (the next step/landing finds them off-path and dismounts quietly), or when a new travel
 * request supersedes it.
 */
class MountTravelSystem(
    private val players: PlayerRegistry,
    private val world: World,
    private val outbound: OutboundBus,
    private val combat: CombatSystem,
    private val scheduler: Scheduler,
    private val gmcpEmitter: GmcpEmitter? = null,
    private val spriteRegistry: SpriteRegistry? = null,
    private val config: MountTravelConfig = MountTravelConfig(),
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val dialogueSystem: dev.ambon.engine.dialogue.DialogueSystem? = null,
    /** Per-room-visited side effects (pet follow, akathavae illumination, flight discovery). */
    private val onPlayerMoved: (suspend (SessionId, RoomId) -> Unit)? = null,
    /** Full room look on arrival (text + GMCP room/shop/bank/... state). */
    private val sendLook: (suspend (SessionId) -> Unit)? = null,
) {
    private class Ride(
        val path: List<RoomId>,
        var index: Int,
        val destName: String,
        /** Milliseconds per hop for this ride — base pace over the mount's speed. */
        val stepMs: Long,
    )

    private class Flight(
        /** Where the flight took off; the rider stays here until landing. */
        val origin: RoomId,
        val dest: RoomId,
        val destName: String,
        val mountName: String,
    )

    private val rides = mutableMapOf<SessionId, Ride>()
    private val flights = mutableMapOf<SessionId, Flight>()

    fun isRiding(sessionId: SessionId): Boolean = sessionId in rides || sessionId in flights

    /** Validates a travel request and, if valid, mounts up and schedules the first step. */
    suspend fun requestTravel(
        sessionId: SessionId,
        destinationRaw: String,
    ) {
        val me = players.get(sessionId) ?: return
        val msgs = config.messages
        if (me.ownedMounts.isEmpty()) {
            sendError(sessionId, msgs.noMount, code = "NO_MOUNT")
            return
        }
        if (combat.isInCombat(sessionId)) {
            sendError(sessionId, msgs.combatBlocked, code = "IN_COMBAT")
            return
        }
        val dest = destinationRaw.trim().takeIf { it.contains(':') }?.let { RoomId(it) }
        val destRoom = dest?.let { world.rooms[it] }
        if (dest == null || destRoom == null) {
            sendError(sessionId, msgs.unknownDestination, code = "UNKNOWN_DESTINATION")
            return
        }
        if (dest == me.roomId) {
            sendError(sessionId, msgs.alreadyHere, code = "ALREADY_HERE")
            return
        }
        if (dest.value !in me.exploredRooms) {
            sendError(sessionId, msgs.notExplored, code = "NOT_EXPLORED")
            return
        }
        val path = findKnownPath(me.roomId, dest, me.exploredRooms)
        if (path != null && path.size <= config.maxPathLength) {
            beginRide(sessionId, me, path, destRoom.title, dest)
            return
        }
        // No known ground route — a flying mount crosses anyway, in one airborne hop.
        val flyingMountId = flightMountId(me)
        if (flyingMountId == null) {
            // Distinguish "buy a flying mount" from plain unreachability only in the code;
            // the message already hints at the upgrade path.
            sendError(sessionId, msgs.noFlyingMount, code = "NO_FLYING_MOUNT")
            return
        }
        beginFlight(sessionId, me, flyingMountId, dest, destRoom.title)
    }

    private suspend fun beginRide(
        sessionId: SessionId,
        me: PlayerState,
        path: List<RoomId>,
        destName: String,
        dest: RoomId,
    ) {
        val msgs = config.messages
        // A new request re-targets any ride already underway (map clicks are the primary UI).
        rides.remove(sessionId)
        flights.remove(sessionId)

        val mountId = rideMountId(me)
        val mountName = spriteRegistry?.mountSprite(mountId)?.displayName ?: "your mount"
        me.ridingMountId = mountId
        metrics.onGameEvent("mountTravel", "start")

        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                msgs.mountUp.replace("{mount}", mountName).replace("{dest}", destName),
            ),
        )
        if (!me.invisible) {
            for (other in players.playersInRoom(me.roomId).filter { it.sessionId != sessionId }) {
                outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} ${msgs.mountUpNotice}"))
                // Refresh the rider's sprite for bystanders now that the mount shows.
                gmcpEmitter?.sendRoomAddPlayer(other.sessionId, me)
            }
        }
        gmcpEmitter?.sendTravelStatus(sessionId, me, destination = dest.value)

        val ride = Ride(path = path, index = 0, destName = destName, stepMs = stepMs(mountId))
        rides[sessionId] = ride
        scheduler.scheduleIn(ride.stepMs) { step(sessionId, ride) }
    }

    private suspend fun beginFlight(
        sessionId: SessionId,
        me: PlayerState,
        mountId: String,
        dest: RoomId,
        destName: String,
    ) {
        val msgs = config.messages
        rides.remove(sessionId)
        flights.remove(sessionId)

        val mountName = spriteRegistry?.mountSprite(mountId)?.displayName ?: "your mount"
        me.ridingMountId = mountId
        metrics.onGameEvent("mountTravel", "takeOff")

        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                msgs.takeOff.replace("{mount}", mountName).replace("{dest}", destName),
            ),
        )
        if (!me.invisible) {
            for (other in players.playersInRoom(me.roomId).filter { it.sessionId != sessionId }) {
                outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} ${msgs.takeOffNotice}"))
                gmcpEmitter?.sendRoomAddPlayer(other.sessionId, me)
            }
        }
        gmcpEmitter?.sendTravelStatus(sessionId, me, destination = dest.value, flying = true)

        val flight = Flight(origin = me.roomId, dest = dest, destName = destName, mountName = mountName)
        flights[sessionId] = flight
        scheduler.scheduleIn(flightMs(me.roomId.zone, dest.zone)) { land(sessionId, flight) }
    }

    /** Dismounts in place (e.g. combat interruption), optionally telling the player why. */
    suspend fun cancelRide(
        sessionId: SessionId,
        notice: String? = null,
    ) {
        val hadRide = rides.remove(sessionId) != null
        val hadFlight = flights.remove(sessionId) != null
        if (!hadRide && !hadFlight) return
        val me = players.get(sessionId) ?: return
        me.ridingMountId = null
        if (notice != null) outbound.send(OutboundEvent.SendText(sessionId, notice))
        gmcpEmitter?.sendTravelStatus(sessionId, me)
        metrics.onGameEvent("mountTravel", "cancel")
    }

    /** The scheduled landing of a flight: one teleport hop to the destination. */
    private suspend fun land(
        sessionId: SessionId,
        flight: Flight,
    ) {
        if (flights[sessionId] !== flight) return // superseded or cancelled
        val me = players.get(sessionId)
        if (me == null) {
            flights.remove(sessionId)
            return
        }
        if (combat.isInCombat(sessionId)) {
            cancelRide(sessionId, config.messages.flightInterrupted)
            return
        }
        if (me.roomId != flight.origin) {
            // Moved by other means (recall, teleport, manual walk) — dismount quietly.
            cancelRide(sessionId)
            return
        }
        val destRoom = world.rooms[flight.dest]
        if (destRoom == null) {
            // World changed under the flight (hot reload) — dismount quietly.
            cancelRide(sessionId)
            return
        }
        flights.remove(sessionId)
        movePlayerWithNotify(
            sessionId,
            from = flight.origin,
            to = flight.dest,
            departMsg = config.messages.departNotice,
            arriveMsg = config.messages.landNotice,
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
            dialogueSystem = dialogueSystem,
            world = world,
        )
        onPlayerMoved?.invoke(sessionId, flight.dest)
        me.ridingMountId = null
        metrics.onGameEvent("mountTravel", "land")
        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                config.messages.landing
                    .replace("{mount}", flight.mountName)
                    .replace("{dest}", flight.destName),
            ),
        )
        if (!me.invisible) {
            // movePlayerWithNotify already announced the landing; just refresh
            // the rider's sprite for bystanders now that the mount is gone.
            for (other in players.playersInRoom(flight.dest).filter { it.sessionId != sessionId }) {
                gmcpEmitter?.sendRoomAddPlayer(other.sessionId, me)
            }
        }
        sendLook?.invoke(sessionId)
        gmcpEmitter?.sendTravelStatus(sessionId, me)
    }

    /** One scheduled hop along the path; re-schedules itself until arrival or cancellation. */
    private suspend fun step(
        sessionId: SessionId,
        ride: Ride,
    ) {
        if (rides[sessionId] !== ride) return // superseded or cancelled
        val me = players.get(sessionId)
        if (me == null) {
            rides.remove(sessionId)
            return
        }
        if (combat.isInCombat(sessionId)) {
            cancelRide(sessionId, config.messages.combatInterrupted)
            return
        }
        val expected = ride.path[ride.index]
        if (me.roomId != expected) {
            // Moved by other means (recall, teleport, manual walk) — dismount quietly.
            cancelRide(sessionId)
            return
        }
        val next = ride.path[ride.index + 1]
        val fromRoom = world.rooms[expected]
        val nextRoom = world.rooms[next]
        if (fromRoom == null || nextRoom == null) {
            // World changed under the ride (hot reload) — dismount quietly.
            cancelRide(sessionId)
            return
        }
        val direction = fromRoom.exits.entries.firstOrNull { it.value == next }?.key
        movePlayerWithNotify(
            sessionId,
            from = expected,
            to = next,
            departMsg = "rides off.",
            arriveMsg = "rides through.",
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
            dialogueSystem = dialogueSystem,
            direction = direction,
            departVerb = "rides",
            world = world,
        )
        onPlayerMoved?.invoke(sessionId, next)
        ride.index++

        if (ride.index == ride.path.lastIndex) {
            rides.remove(sessionId)
            me.ridingMountId = null
            metrics.onGameEvent("mountTravel", "arrive")
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    config.messages.arrival.replace("{dest}", ride.destName),
                ),
            )
            if (!me.invisible) {
                for (other in players.playersInRoom(next).filter { it.sessionId != sessionId }) {
                    outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} ${config.messages.arriveNotice}"))
                    gmcpEmitter?.sendRoomAddPlayer(other.sessionId, me)
                }
            }
            sendLook?.invoke(sessionId)
            gmcpEmitter?.sendTravelStatus(sessionId, me)
        } else {
            // Mid-ride: keep the map animating without spamming full room text.
            gmcpEmitter?.sendRoomInfo(sessionId, nextRoom)
            scheduler.scheduleIn(ride.stepMs) { step(sessionId, ride) }
        }
    }

    /**
     * The mount to ride: the mount matching the player's active sprite when that
     * sprite is one of their mounts, otherwise their fastest-owned mount.
     */
    private fun rideMountId(me: PlayerState): String {
        activeSpriteMountId(me)?.let { return it }
        return me.ownedMounts.maxByOrNull { mountSpeed(it) } ?: me.ownedMounts.first()
    }

    /**
     * The mount to fly: the active-sprite mount when it flies, otherwise the
     * fastest flying mount owned. Null when the player owns no flying mount.
     */
    private fun flightMountId(me: PlayerState): String? {
        activeSpriteMountId(me)?.takeIf { world.mountStats(it)?.flying == true }?.let { return it }
        return me.ownedMounts
            .filter { world.mountStats(it)?.flying == true }
            .maxByOrNull { mountSpeed(it) }
    }

    /** The owned mount backing the player's active sprite, if any. */
    private fun activeSpriteMountId(me: PlayerState): String? {
        val active = me.activeSprite ?: return null
        val def = spriteRegistry?.findVariant(active)?.first ?: return null
        if (def.category != SpriteCategory.MOUNT) return null
        val mountId = def.requirements
            .filterIsInstance<dev.ambon.domain.sprite.SpriteRequirement.Mount>()
            .firstOrNull()
            ?.mountId
        return mountId?.takeIf { it in me.ownedMounts }
    }

    /** Speed multiplier for [mountId]; mounts no longer sold anywhere ride at base pace. */
    private fun mountSpeed(mountId: String): Double = world.mountStats(mountId)?.speed ?: 1.0

    /** Milliseconds per ride hop for [mountId] — the base pace over the mount's speed. */
    private fun stepMs(mountId: String): Long =
        (config.msPerRoom / mountSpeed(mountId)).roundToLong().coerceAtLeast(1L)

    /**
     * Airborne milliseconds for a flight from [fromZone] to [toZone]: base plus a
     * per-percent rate over the straight-line distance between the zones' world-map
     * centres, clamped. Zones unplaced on the world map fly at the clamp midpoint.
     */
    private fun flightMs(
        fromZone: String,
        toZone: String,
    ): Long {
        val a = world.zoneWorldMap(fromZone)
        val b = world.zoneWorldMap(toZone)
        if (a == null || b == null) return (config.flightMsMin + config.flightMsMax) / 2
        val dx = (a.x + a.w / 2) - (b.x + b.w / 2)
        val dy = (a.y + a.h / 2) - (b.y + b.h / 2)
        val distancePercent = sqrt(dx * dx + dy * dy)
        return (config.flightMsBase + (config.flightMsPerMapPercent * distancePercent).roundToLong())
            .coerceIn(config.flightMsMin, config.flightMsMax)
    }

    /**
     * Shortest path from [origin] to [dest] over explored rooms only. Every hop stays in
     * [origin]'s zone except the final arrival at [dest], which may be one hop into an
     * adjacent zone. Returns the inclusive room list origin..dest, or null when unreachable.
     */
    private fun findKnownPath(
        origin: RoomId,
        dest: RoomId,
        explored: Set<String>,
    ): List<RoomId>? {
        if (world.rooms[origin] == null) return null
        val zone = origin.zone
        val parents = HashMap<RoomId, RoomId>()
        val visited = HashSet<RoomId>()
        visited.add(origin)
        val queue = ArrayDeque<RoomId>()
        queue.add(origin)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == dest) {
                val path = ArrayDeque<RoomId>()
                var node = dest
                while (node != origin) {
                    path.addFirst(node)
                    node = parents.getValue(node)
                }
                path.addFirst(origin)
                return path.toList()
            }
            val room = world.rooms[current] ?: continue
            for (next in room.exits.values) {
                if (next in visited) continue
                if (world.rooms[next] == null) continue
                if (next.value !in explored) continue
                // Ride within the current zone; only the destination may lie beyond it.
                if (next.zone != zone && next != dest) continue
                visited.add(next)
                parents[next] = current
                queue.add(next)
            }
        }
        return null
    }

    private suspend fun sendError(
        sessionId: SessionId,
        message: String,
        code: String,
    ) {
        outbound.send(OutboundEvent.SendError(sessionId, message))
        gmcpEmitter?.sendUiFeedback(sessionId, "error", message, code = code, scope = "travel", command = "travel")
    }
}
