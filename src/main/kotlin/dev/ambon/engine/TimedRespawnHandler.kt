package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.LockableState
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.World
import dev.ambon.engine.commands.handlers.buildFeaturePayload
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import java.time.Clock

/**
 * Restores individual item spawns and stateful room features that declare
 * `respawnSeconds`, independently of the coarse zone-lifespan reset.
 *
 * - A room-placed item spawn with `respawnSeconds` is re-placed in its room
 *   that many seconds after the instance is first observed missing from it.
 * - A door/lever/container with `respawnSeconds` reverts to its authored
 *   initial condition that many seconds after it is first observed out of it
 *   (state changed, or — for containers — contents differing from
 *   `initialItems`). Reverting a container refills its initial contents,
 *   mirroring what the zone reset does for `resetWithZone` containers.
 *
 * Timers are observation-based (tick resolution) and reset whenever the spawn
 * or feature returns to its initial condition by any means, including a zone
 * reset. State is in-memory only: a restart re-arms timers from scratch.
 *
 * Called exclusively from the single-threaded engine dispatcher.
 */
internal class TimedRespawnHandler(
    private val world: World,
    private val items: ItemRegistry,
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val worldState: WorldStateRegistry,
    private val gmcpEmitter: GmcpEmitter?,
    private val clock: Clock,
) {
    private var trackedItemSpawns: List<ItemSpawn> = computeTrackedItems()
    private var trackedFeatureIds: List<String> = computeTrackedFeatures()

    /** When each tracked item instance was first observed missing from its spawn room. */
    private val itemMissingSinceMs = mutableMapOf<ItemId, Long>()

    /** When each tracked feature was first observed out of its initial condition. */
    private val featureChangedSinceMs = mutableMapOf<String, Long>()

    /** Re-scans the world for tracked spawns/features. Call after a hot reload. */
    fun refresh() {
        trackedItemSpawns = computeTrackedItems()
        trackedFeatureIds = computeTrackedFeatures()
        itemMissingSinceMs.clear()
        featureChangedSinceMs.clear()
    }

    private fun computeTrackedItems(): List<ItemSpawn> =
        world.itemSpawns.filter { it.respawnSeconds != null && it.roomId != null }

    private fun computeTrackedFeatures(): List<String> =
        worldState.featuresById.values
            .filter { feature -> feature.respawnSecondsOrNull() != null }
            .map { it.id }

    /** Called once per engine tick. */
    suspend fun tick() {
        if (trackedItemSpawns.isEmpty() && trackedFeatureIds.isEmpty()) return
        val now = clock.millis()
        tickItems(now)
        tickFeatures(now)
    }

    private suspend fun tickItems(now: Long) {
        for (spawn in trackedItemSpawns) {
            val roomId = spawn.roomId ?: continue
            val respawnMs = (spawn.respawnSeconds ?: continue) * 1000L
            val instanceId = spawn.instance.id
            val present = items.itemsInRoom(roomId).any { it.id == instanceId }
            if (present) {
                itemMissingSinceMs.remove(instanceId)
                continue
            }
            val missingSince = itemMissingSinceMs.getOrPut(instanceId) { now }
            if (now - missingSince < respawnMs) continue

            itemMissingSinceMs.remove(instanceId)
            items.addRoomItem(roomId, spawn.instance)
            for (p in players.playersInRoom(roomId)) {
                outbound.send(OutboundEvent.SendText(p.sessionId, "${spawn.instance.item.displayName} appears."))
                gmcpEmitter?.sendRoomItems(p.sessionId, items.itemsInRoom(roomId))
            }
        }
    }

    private suspend fun tickFeatures(now: Long) {
        for (featureId in trackedFeatureIds) {
            val feature = worldState.featuresById[featureId] ?: continue
            val respawnSeconds = feature.respawnSecondsOrNull() ?: continue
            if (isInInitialCondition(feature)) {
                featureChangedSinceMs.remove(featureId)
                continue
            }
            val changedSince = featureChangedSinceMs.getOrPut(featureId) { now }
            if (now - changedSince < respawnSeconds * 1000L) continue

            featureChangedSinceMs.remove(featureId)
            revertFeature(feature)
        }
    }

    private fun isInInitialCondition(feature: RoomFeature): Boolean = when (feature) {
        is RoomFeature.Door -> worldState.getLockableState(feature.id) == feature.initialState
        is RoomFeature.Lever -> worldState.getLeverState(feature.id) == feature.initialState
        is RoomFeature.Container ->
            worldState.getLockableState(feature.id) == feature.initialState && contentsMatchInitial(feature)
        is RoomFeature.Sign -> true
    }

    private fun contentsMatchInitial(feature: RoomFeature.Container): Boolean {
        val current = worldState.getContainerContents(feature.id).map { it.id.value }.sorted()
        val initial = feature.initialItems.map { it.value }.sorted()
        return current == initial
    }

    private suspend fun revertFeature(feature: RoomFeature) {
        val message = when (feature) {
            is RoomFeature.Door -> {
                worldState.setLockableState(feature.id, feature.initialState)
                if (feature.initialState == LockableState.OPEN) {
                    "The ${feature.displayName} swings open."
                } else {
                    "The ${feature.displayName} swings shut."
                }
            }
            is RoomFeature.Container -> {
                worldState.setLockableState(feature.id, feature.initialState)
                val instances = feature.initialItems.mapNotNull { items.createFromTemplate(it) }
                worldState.resetContainer(feature.id, instances)
                if (feature.initialState == LockableState.OPEN) {
                    "${feature.displayName} creaks open, its contents restored."
                } else {
                    "${feature.displayName} clicks shut."
                }
            }
            is RoomFeature.Lever -> {
                worldState.setLeverState(feature.id, feature.initialState)
                "${feature.displayName} snaps back into place."
            }
            is RoomFeature.Sign -> return
        }
        for (p in players.playersInRoom(feature.roomId)) {
            outbound.send(OutboundEvent.SendText(p.sessionId, message))
        }
        emitRoomFeatures(feature.roomId)
    }

    private suspend fun emitRoomFeatures(roomId: RoomId) {
        val emitter = gmcpEmitter ?: return
        val room = world.rooms[roomId] ?: return
        val payloads = room.features.map { buildFeaturePayload(it, worldState) }
        for (p in players.playersInRoom(roomId)) {
            emitter.sendRoomFeatures(p.sessionId, payloads)
        }
    }
}

private fun RoomFeature.respawnSecondsOrNull(): Long? = when (this) {
    is RoomFeature.Door -> respawnSeconds
    is RoomFeature.Container -> respawnSeconds
    is RoomFeature.Lever -> respawnSeconds
    is RoomFeature.Sign -> null
}
