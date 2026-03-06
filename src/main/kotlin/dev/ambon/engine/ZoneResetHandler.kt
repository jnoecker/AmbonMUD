package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.idZone
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.World
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import java.time.Clock

/** BFS from [start], staying within the same zone. Returns room → hop distance. */
internal fun computeDistanceMap(start: RoomId, world: World): Map<RoomId, Int> {
    val zone = start.zone
    val distances = mutableMapOf(start to 0)
    val queue = ArrayDeque<RoomId>()
    queue.add(start)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val dist = distances[current]!!
        val room = world.rooms[current] ?: continue
        for (exit in room.exits.values) {
            if (exit.zone != zone) continue
            if (exit in distances) continue
            distances[exit] = dist + 1
            queue.add(exit)
        }
    }
    return distances
}

/** Converts a [MobSpawn] definition into a live [MobState] instance. */
internal fun spawnToMobState(spawn: MobSpawn, world: World): MobState =
    MobState(
        id = spawn.id,
        name = spawn.name,
        roomId = spawn.roomId,
        hp = spawn.maxHp,
        maxHp = spawn.maxHp,
        damage = spawn.damage,
        armor = spawn.armor,
        xpReward = spawn.xpReward,
        drops = spawn.drops,
        goldMin = spawn.goldMin,
        goldMax = spawn.goldMax,
        dialogue = spawn.dialogue,
        behaviorTree = spawn.behaviorTree,
        aggressive = spawn.aggressive,
        templateKey = spawn.id.value,
        spawnRoomId = spawn.roomId,
        spawnDistanceMap = computeDistanceMap(spawn.roomId, world),
        questIds = spawn.questIds,
        image = spawn.image,
        video = spawn.video,
    )

/**
 * Handles periodic zone resets: tracks per-zone lifespan timers, removes stale mobs/items,
 * re-spawns them from world definitions, and resets stateful room features.
 */
internal class ZoneResetHandler(
    private val world: World,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val worldState: WorldStateRegistry,
    private val mobRemovalCoordinator: MobRemovalCoordinator,
    private val mobSystem: MobSystem,
    private val behaviorTreeSystem: BehaviorTreeSystem,
    private val gmcpEmitter: GmcpEmitter,
    private val clock: Clock,
) {
    private val zoneResetDueAtMillis: MutableMap<String, Long> =
        world.zoneLifespansMinutes
            .filterValues { it > 0L }
            .mapValuesTo(mutableMapOf()) { (_, minutes) -> clock.millis() + minutesToMillis(minutes) }

    /** Called once per tick; resets any zones whose lifespan has elapsed. */
    suspend fun tick() {
        if (zoneResetDueAtMillis.isEmpty()) return

        val now = clock.millis()
        for ((zone, dueAtMillis) in zoneResetDueAtMillis) {
            if (now < dueAtMillis) continue

            resetZone(zone)

            val lifespanMinutes = world.zoneLifespansMinutes[zone] ?: continue
            val lifespanMillis = minutesToMillis(lifespanMinutes)
            val elapsedCycles = ((now - dueAtMillis) / lifespanMillis) + 1
            zoneResetDueAtMillis[zone] = dueAtMillis + (elapsedCycles * lifespanMillis)
        }
    }

    private suspend fun resetZone(zone: String) {
        val playersInZone = players.allPlayers().filter { player -> player.roomId.zone == zone }
        for (player in playersInZone) {
            outbound.send(OutboundEvent.SendText(player.sessionId, "The air shimmers as the area resets around you."))
        }

        val zoneRoomIds =
            world.rooms.keys
                .filterTo(linkedSetOf()) { roomId -> roomId.zone == zone }

        val zoneMobSpawns =
            world.mobSpawns
                .filter { spawn -> idZone(spawn.id.value) == zone }
        val activeZoneMobIds =
            mobs
                .all()
                .map { mob -> mob.id }
                .filter { mobId -> idZone(mobId.value) == zone }

        val zoneMobIds =
            (zoneMobSpawns.map { spawn -> spawn.id } + activeZoneMobIds)
                .toSet()

        for (mobId in zoneMobIds) {
            mobRemovalCoordinator.removeMobExternally(mobId)
        }

        for (spawn in zoneMobSpawns) {
            mobs.upsert(spawnToMobState(spawn, world))
            mobSystem.onMobSpawned(spawn.id)
            behaviorTreeSystem.onMobSpawned(spawn.id)
        }

        val zoneItemSpawns =
            world.itemSpawns
                .filter { spawn -> idZone(spawn.instance.id.value) == zone }

        items.resetZone(
            zone = zone,
            roomIds = zoneRoomIds,
            mobIds = zoneMobIds,
            spawns = zoneItemSpawns,
        )

        // Reset stateful room features (doors, containers, levers) for this zone.
        worldState.resetZone(zone)
        for (room in world.rooms.values.filter { it.id.zone == zone }) {
            for (feature in room.features.filterIsInstance<RoomFeature.Container>()) {
                if (!feature.resetWithZone) continue
                val instances = feature.initialItems.mapNotNull { items.createFromTemplate(it) }
                worldState.resetContainer(feature.id, instances)
            }
        }

        // Refresh mob GMCP for all players in the reset zone.
        for (player in playersInZone) {
            gmcpEmitter.sendRoomMobs(player.sessionId, mobs.mobsInRoom(player.roomId))
            gmcpEmitter.sendRoomItems(player.sessionId, items.itemsInRoom(player.roomId))
        }
    }
}

private fun minutesToMillis(minutes: Long): Long = minutes * 60_000L
