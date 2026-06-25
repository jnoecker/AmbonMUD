package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.config.FlightConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.metrics.GameMetrics

/**
 * Flight masters — room kiosks that let players pay gold to fast-travel between flight points
 * they have personally discovered by visiting.
 *
 * The network is per-player and discovery-gated: standing in a `flightMaster` room records it
 * in [PlayerState.discoveredFlightPoints]. From any flight master you may then fly to any other
 * discovered point. The fare scales with travel distance — the BFS hop count between the current
 * room and the destination over the world's exit graph — clamped to [FlightConfig.minCost]..
 * [FlightConfig.maxCost]. Destinations that aren't reachable on this engine (e.g. a different
 * zone not loaded here) fall back to [FlightConfig.unreachableCost].
 *
 * This system owns the read-side logic (discovery, pricing, destination listing); the actual
 * teleport + gold charge live in `FlightHandler`.
 */
class FlightSystem(
    private val players: PlayerRegistry,
    private val world: World,
    private val outbound: OutboundBus,
    private val config: FlightConfig = FlightConfig(),
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val markVitalsDirty: ((SessionId) -> Unit)? = null,
) {
    /** A reachable (or known-but-unreachable) destination from the player's current room. */
    data class Destination(
        val roomId: RoomId,
        val name: String,
        val zone: String,
        /** BFS hops from the current room, or null when unreachable on this engine. */
        val distance: Int?,
        val cost: Long,
    )

    /** True if [roomId] hosts a flight master right now. */
    fun isFlightMaster(roomId: RoomId): Boolean = world.rooms[roomId]?.flightMaster == true

    /**
     * Records the player's current room as a discovered flight point. Fires on every movement;
     * a no-op unless the player is standing in a flight master they haven't recorded yet.
     */
    suspend fun onRoomVisited(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        val roomId = me.roomId
        if (!isFlightMaster(roomId)) return
        if (!me.discoveredFlightPoints.add(roomId.value)) return
        markVitalsDirty?.invoke(sessionId)
        metrics.onGameEvent("flight", "discover")
        outbound.send(OutboundEvent.SendInfo(sessionId, config.messages.discovered))
    }

    /**
     * Destinations the player can currently fly to: every discovered flight point that still
     * exists and still hosts a flight master, excluding the room they're standing in. Sorted by
     * zone then name. Distances are computed with a single BFS from [origin].
     */
    fun destinationsFrom(
        origin: RoomId,
        discovered: Set<String>,
    ): List<Destination> {
        val distances = bfsDistances(origin)
        return discovered
            .asSequence()
            .map { RoomId(it) }
            .filter { it != origin }
            .mapNotNull { roomId ->
                val room = world.rooms[roomId] ?: return@mapNotNull null
                if (!room.flightMaster) return@mapNotNull null
                val dist = distances[roomId]
                Destination(
                    roomId = roomId,
                    name = room.title,
                    zone = roomId.zone,
                    distance = dist,
                    cost = costForDistance(dist),
                )
            }
            .sortedWith(compareBy({ it.zone }, { it.name }))
            .toList()
    }

    /** Fare for a flight covering [distance] hops; null distance means unreachable. */
    fun costForDistance(distance: Int?): Long {
        if (distance == null) return config.unreachableCost
        val raw = config.baseCost + config.costPerRoom * distance
        return raw.coerceIn(config.minCost, config.maxCost)
    }

    /** Breadth-first hop distances from [origin] to every reachable room over the exit graph. */
    private fun bfsDistances(origin: RoomId): Map<RoomId, Int> {
        if (world.rooms[origin] == null) return emptyMap()
        val distances = HashMap<RoomId, Int>()
        distances[origin] = 0
        val queue = ArrayDeque<RoomId>()
        queue.add(origin)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val depth = distances.getValue(current)
            val room = world.rooms[current] ?: continue
            for (next in room.exits.values) {
                if (next in distances) continue
                if (world.rooms[next] == null) continue
                distances[next] = depth + 1
                queue.add(next)
            }
        }
        return distances
    }
}
