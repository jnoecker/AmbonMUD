package dev.ambon.engine

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.ContainerState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.DoorState
import dev.ambon.domain.world.LeverState
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.World
import dev.ambon.persistence.WorldStateSnapshot
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Mutable runtime state for all stateful room features (doors, containers, levers).
 *
 * All methods are called exclusively from the single-threaded engine dispatcher.
 * Thread-safety between the engine and the persistence worker is handled via
 * [isDirty] (volatile) and [buildSnapshot] which must be called on the engine thread.
 */
class WorldStateRegistry(
    world: World,
) {
    /** All feature definitions indexed by ID. */
    val featuresById: Map<String, RoomFeature>

    /** All feature definitions indexed by room. */
    val featuresByRoom: Map<RoomId, List<RoomFeature>>

    /** Doors indexed by (roomId, direction) for fast movement-blocking lookup. */
    private val doorsByRoomAndDir: Map<Pair<RoomId, Direction>, RoomFeature.Door>

    init {
        val byId = mutableMapOf<String, RoomFeature>()
        val byRoom = mutableMapOf<RoomId, MutableList<RoomFeature>>()
        val byDir = mutableMapOf<Pair<RoomId, Direction>, RoomFeature.Door>()

        for (room in world.rooms.values) {
            for (feature in room.features) {
                byId[feature.id] = feature
                byRoom.getOrPut(room.id) { mutableListOf() }.add(feature)
                if (feature is RoomFeature.Door) {
                    byDir[room.id to feature.direction] = feature
                }
            }
        }

        featuresById = byId
        featuresByRoom = byRoom.mapValues { (_, list) -> list.toList() }
        doorsByRoomAndDir = byDir
    }

    // ---- Mutable runtime state ----

    private val doorStates = mutableMapOf<String, DoorState>()
    private val containerStates = mutableMapOf<String, ContainerState>()
    private val leverStates = mutableMapOf<String, LeverState>()

    /** Items currently inside each container. Key = feature ID. */
    private val containerContents = mutableMapOf<String, MutableList<ItemInstance>>()

    @Volatile
    var isDirty: Boolean = false
        private set

    // ---- Door operations ----

    fun getDoorState(featureId: String): DoorState {
        val def = featuresById[featureId] as? RoomFeature.Door ?: return DoorState.CLOSED
        return doorStates[featureId] ?: def.initialState
    }

    fun setDoorState(
        featureId: String,
        state: DoorState,
    ) {
        doorStates[featureId] = state
        isDirty = true
    }

    // ---- Container operations ----

    fun getContainerState(featureId: String): ContainerState {
        val def = featuresById[featureId] as? RoomFeature.Container ?: return ContainerState.CLOSED
        return containerStates[featureId] ?: def.initialState
    }

    fun setContainerState(
        featureId: String,
        state: ContainerState,
    ) {
        containerStates[featureId] = state
        isDirty = true
    }

    fun getContainerContents(featureId: String): List<ItemInstance> =
        containerContents[featureId] ?: emptyList()

    fun addToContainer(
        featureId: String,
        item: ItemInstance,
    ) {
        containerContents.getOrPut(featureId) { mutableListOf() }.add(item)
        isDirty = true
    }

    fun removeFromContainer(
        featureId: String,
        keyword: String,
    ): ItemInstance? {
        val contents = containerContents[featureId] ?: return null
        val idx = contents.indexOfFirst { matchesKeyword(it, keyword) }
        if (idx < 0) return null
        isDirty = true
        return contents.removeAt(idx)
    }

    fun resetContainer(
        featureId: String,
        items: List<ItemInstance>,
    ) {
        containerContents[featureId] = items.toMutableList()
        isDirty = true
    }

    // ---- Lever operations ----

    fun getLeverState(featureId: String): LeverState {
        val def = featuresById[featureId] as? RoomFeature.Lever ?: return LeverState.UP
        return leverStates[featureId] ?: def.initialState
    }

    fun setLeverState(
        featureId: String,
        state: LeverState,
    ) {
        leverStates[featureId] = state
        isDirty = true
    }

    // ---- Lookup helpers ----

    fun doorOnExit(
        roomId: RoomId,
        direction: Direction,
    ): RoomFeature.Door? = doorsByRoomAndDir[roomId to direction]

    fun findFeatureByKeyword(
        roomId: RoomId,
        keyword: String,
    ): RoomFeature? {
        val lower = keyword.lowercase()
        return featuresByRoom[roomId]?.firstOrNull { feature ->
            feature.keyword.lowercase() == lower ||
                feature.displayName.lowercase().contains(lower)
        }
    }

    // ---- Zone reset ----

    fun resetZone(zone: String) {
        for ((id, feature) in featuresById) {
            if (feature.roomId.zone != zone) continue
            when (feature) {
                is RoomFeature.Door -> {
                    if (feature.resetWithZone) {
                        doorStates[id] = feature.initialState
                        isDirty = true
                    }
                }
                is RoomFeature.Container -> {
                    if (feature.resetWithZone) {
                        containerStates[id] = feature.initialState
                        // Container contents are reset by GameEngine (needs ItemRegistry access)
                        isDirty = true
                    }
                }
                is RoomFeature.Lever -> {
                    if (feature.resetWithZone) {
                        leverStates[id] = feature.initialState
                        isDirty = true
                    }
                }
                is RoomFeature.Sign -> Unit
            }
        }
    }

    // ---- Dirty tracking + persistence ----

    /** Build a snapshot of the current full state. Must be called on the engine thread. */
    fun buildSnapshot(): WorldStateSnapshot {
        val containerItemsMap =
            containerContents.mapValues { (_, items) ->
                items.map { it.id.value }
            }
        return WorldStateSnapshot(
            doorStates = doorStates.mapValues { (_, s) -> s.name },
            containerStates = containerStates.mapValues { (_, s) -> s.name },
            leverStates = leverStates.mapValues { (_, s) -> s.name },
            containerItems = containerItemsMap,
        )
    }

    fun clearDirty() {
        isDirty = false
    }

    /**
     * Apply a persisted snapshot, overriding the defaults from feature definitions.
     * Item instances for containers are NOT restored here — the caller must resolve
     * ItemIds to ItemInstances using the ItemRegistry.
     */
    fun applySnapshot(
        snapshot: WorldStateSnapshot,
        resolveItem: (ItemId) -> ItemInstance?,
    ) {
        for ((id, stateName) in snapshot.doorStates) {
            val state = DoorState.entries.firstOrNull { it.name == stateName } ?: continue
            doorStates[id] = state
        }
        for ((id, stateName) in snapshot.containerStates) {
            val state = ContainerState.entries.firstOrNull { it.name == stateName } ?: continue
            containerStates[id] = state
        }
        for ((id, stateName) in snapshot.leverStates) {
            val state = LeverState.entries.firstOrNull { it.name == stateName } ?: continue
            leverStates[id] = state
        }
        for ((featureId, itemIds) in snapshot.containerItems) {
            val instances =
                itemIds.mapNotNull { rawId ->
                    val instance = resolveItem(ItemId(rawId))
                    if (instance == null) {
                        log.warn {
                            "WorldStateRegistry.applySnapshot: item '$rawId' in container '$featureId' could not be resolved — item removed from game?"
                        }
                    }
                    instance
                }
            // Always overwrite — empty list means container was deliberately emptied.
            containerContents[featureId] = instances.toMutableList()
        }
        isDirty = false
    }

    // ---- Private helpers ----

    private fun matchesKeyword(
        item: ItemInstance,
        keyword: String,
    ): Boolean {
        val lower = keyword.lowercase()
        return item.item.keyword.lowercase() == lower ||
            (lower.length >= 3 && item.item.displayName.lowercase().contains(lower))
    }
}
