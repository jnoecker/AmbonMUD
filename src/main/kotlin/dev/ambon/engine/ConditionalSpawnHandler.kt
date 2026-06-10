package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.World
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.events.OutboundEvent
import java.time.Clock
import java.util.Random

/**
 * True when [this] spawn's lifecycle is owned by [ConditionalSpawnHandler] —
 * i.e. its template carries a non-trivial spawn condition. Such spawns are
 * skipped by the cold-start, zone-reset, and post-death respawn paths.
 */
internal fun MobSpawn.isConditional(world: World): Boolean {
    val cond = world.mobTemplate(templateId)?.spawnCondition
    return cond != null && !cond.isUnconditional
}

/**
 * Owns the entire spawn lifecycle of condition-gated mobs (a mob authored with a
 * non-trivial [dev.ambon.domain.world.SpawnCondition] — night-only, storm-only,
 * winter-only, intermittent-random, etc.). Such spawns are deliberately excluded
 * from the cold-start spawn, zone reset, and post-death respawn paths so this
 * handler is their single source of truth.
 *
 * On a throttled cadence it re-evaluates each conditional spawn:
 * - **gates satisfied, mob absent** → roll [dev.ambon.domain.world.SpawnCondition.chance];
 *   on success spawn it (possibly as a rare variant) and announce its arrival.
 * - **gates no longer satisfied, mob present** → fade it out — but never while it
 *   is fighting a player (a mob yanked mid-swing forfeits the kill and feels broken).
 *
 * Runs exclusively on the single-threaded engine dispatcher.
 */
internal class ConditionalSpawnHandler(
    private val world: World,
    private val mobs: MobRegistry,
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val gmcpEmitter: GmcpEmitter,
    private val mobSystem: MobSystem,
    private val behaviorTreeSystem: BehaviorTreeSystem,
    private val mobRemovalCoordinator: MobRemovalCoordinator,
    private val variantRoller: MobVariantRoller,
    private val period: () -> TimePeriod,
    private val season: () -> Season,
    private val weatherForZone: (String) -> String,
    private val activeEventFlags: () -> Set<String>,
    private val isMobInCombat: (MobId) -> Boolean,
    private val clock: Clock,
    private val checkIntervalMs: Long = 10_000L,
    private val rng: Random = Random(),
) {
    private var conditionalSpawns: List<MobSpawn> = computeConditionalSpawns()
    private var nextCheckMs: Long = clock.millis()

    /** Re-scans the world for conditional spawns. Call after a hot reload. */
    fun refresh() {
        conditionalSpawns = computeConditionalSpawns()
        nextCheckMs = clock.millis()
    }

    private fun computeConditionalSpawns(): List<MobSpawn> =
        world.mobSpawns.filter { spawn ->
            val cond = world.mobTemplate(spawn.templateId)?.spawnCondition
            cond != null && !cond.isUnconditional
        }

    /** Called once per engine tick; re-evaluates conditional spawns on a throttled cadence. */
    suspend fun tick() {
        if (conditionalSpawns.isEmpty()) return
        val now = clock.millis()
        if (now < nextCheckMs) return
        nextCheckMs = now + checkIntervalMs

        val currentPeriod = period()
        val currentSeason = season()
        val flags = activeEventFlags()
        for (spawn in conditionalSpawns) {
            val cond = world.mobTemplate(spawn.templateId)?.spawnCondition ?: continue
            if (world.rooms[spawn.roomId] == null) continue
            val gatesOk = cond.gatesSatisfied(currentPeriod, weatherForZone(spawn.roomId.zone), currentSeason, flags)
            val alive = mobs.get(spawn.id) != null
            when {
                alive && !gatesOk -> {
                    if (!isMobInCombat(spawn.id)) fadeOut(spawn.id)
                }
                !alive && gatesOk -> {
                    if (rng.nextDouble() < cond.chance) spawnConditional(spawn)
                }
            }
        }
    }

    private suspend fun spawnConditional(spawn: MobSpawn) {
        val template = world.mobTemplate(spawn.templateId) ?: return
        val variant = variantRoller.roll(template)
        val referenceLevel = highestPlayerLevelInZone(players, spawn.roomId.zone)
        val mob = spawnToMobState(spawn, world, referenceLevel, variant)
        mobs.upsert(mob)
        mobSystem.onMobSpawned(spawn.id)
        behaviorTreeSystem.onMobSpawned(spawn.id)
        for (p in players.playersInRoom(spawn.roomId)) {
            outbound.send(OutboundEvent.SendText(p.sessionId, "${mob.name} appears."))
            gmcpEmitter.sendRoomAddMob(p.sessionId, mob)
        }
        // A rare creature finally showing up is loud news; variants carry their
        // own rarity-scaled announce, plain conditional mobs get a zone shout.
        if (variant != null) {
            announceMobVariant(outbound, players, mob, variant.def)
        } else {
            val msg = "A creature seldom seen stirs in ${spawn.roomId.zone} — ${mob.name} has appeared!"
            for (p in players.playersInZone(spawn.roomId.zone)) {
                outbound.send(OutboundEvent.SendInfo(p.sessionId, msg))
            }
        }
    }

    private suspend fun fadeOut(mobId: MobId) {
        val mob = mobs.get(mobId) ?: return
        val roomId = mob.roomId
        val name = mob.name
        mobRemovalCoordinator.removeMobExternally(mobId)
        for (p in players.playersInRoom(roomId)) {
            outbound.send(OutboundEvent.SendText(p.sessionId, "$name fades from view, leaving no trace."))
        }
        gmcpEmitter.broadcastRoomRemoveMob(roomId, mobId.value, players)
    }
}
