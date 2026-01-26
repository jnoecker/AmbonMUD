package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.channels.SendChannel
import java.time.Clock
import java.util.Random

class MobSystem(
    private val world: World,
    private val mobs: MobRegistry,
    private val players: PlayerRegistry,
    private val outbound: SendChannel<OutboundEvent>,
    private val clock: Clock = Clock.systemUTC(),
    private val rng: Random = Random(),
    // Tuning knobs (defaults feel “MUD-like”)
    private val minWanderDelayMillis: Long = 2_000L,
    private val maxWanderDelayMillis: Long = 6_000L,
) {
    // Next scheduled action time per mob
    private val nextActAtMillis = mutableMapOf<MobId, Long>()

    /**
     * Called frequently (e.g. every engine tick). This method is time-gated;
     * it will only move mobs whose cooldown has expired.
     */
    suspend fun tick(maxMovesPerTick: Int = 10) {
        val now = clock.millis()
        var moves = 0

        for (m in mobs.all()) {
            if (moves >= maxMovesPerTick) break

            val dueAt = nextActAtMillis[m.id]
            if (dueAt == null) {
                // First time seeing this mob: schedule its initial action.
                nextActAtMillis[m.id] = now + randomDelay()
                continue
            }

            if (now < dueAt) continue

            // If mob has no exits, just reschedule
            val room = world.rooms[m.roomId]
            if (room == null || room.exits.isEmpty()) {
                nextActAtMillis[m.id] = now + randomDelay()
                continue
            }

            // Wander: pick a random exit and move
            val to = pickRandomExit(room.exits.values.toList())
            moveMob(m, to)

            // Reschedule after acting
            nextActAtMillis[m.id] = now + randomDelay()
            moves++
        }
    }

    /**
     * Optional: call this when a mob is removed/despawned so we don’t leak map entries.
     */
    fun onMobRemoved(mobId: MobId) {
        nextActAtMillis.remove(mobId)
    }

    /**
     * Optional: call after spawning/upserting a mob to schedule it immediately.
     * Not required; tick() will lazily schedule on first encounter.
     */
    fun onMobSpawned(mobId: MobId) {
        nextActAtMillis.remove(mobId) // forces fresh scheduling on next tick
    }

    private fun randomDelay(): Long {
        val min = minWanderDelayMillis
        val max = maxWanderDelayMillis
        require(min >= 0) { "minWanderDelayMillis must be >= 0" }
        require(max >= min) { "maxWanderDelayMillis must be >= minWanderDelayMillis" }

        val range = (max - min) + 1
        return min + rng.nextInt(range.toInt()).toLong()
    }

    private fun pickRandomExit(exits: List<RoomId>): RoomId = exits[rng.nextInt(exits.size)]

    private suspend fun moveMob(
        m: MobState,
        to: RoomId,
    ) {
        val from = m.roomId
        if (from == to) return

        // notify players in old room
        for (p in players.playersInRoom(from)) {
            outbound.send(OutboundEvent.SendText(p.sessionId, "${m.name} leaves."))
        }

        mobs.moveTo(m.id, to)

        // notify players in new room
        for (p in players.playersInRoom(to)) {
            outbound.send(OutboundEvent.SendText(p.sessionId, "${m.name} enters."))
        }
    }
}
