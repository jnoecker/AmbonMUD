package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.config.EngineConfig
import dev.ambon.domain.world.World
import dev.ambon.engine.TrainerRegistry
import dev.ambon.engine.abilities.AbilityRegistry
import dev.ambon.engine.abilities.AbilityRegistryLoader
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.crafting.CraftingRegistry
import dev.ambon.engine.crafting.GatheringRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectRegistryLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred

private val log = KotlinLogging.logger {}

/**
 * A reload request posted from an external thread (e.g. admin HTTP API).
 * The engine drains these during each tick and completes the [result] deferred.
 */
data class ReloadRequest(
    val target: String?,
    val result: CompletableDeferred<String>,
)

/**
 * Result of a hot reload operation.
 */
data class HotReloadResult(
    val zonesReloaded: Int = 0,
    val roomsAdded: Int = 0,
    val roomsRemoved: Int = 0,
    val mobSpawnsAdded: Int = 0,
    val itemSpawnsAdded: Int = 0,
    val abilitiesReloaded: Int = 0,
    val statusEffectsReloaded: Int = 0,
    val playersRelocated: Int = 0,
    val errors: List<String> = emptyList(),
) {
    fun summary(): String = buildString {
        append("Hot reload complete.")
        if (zonesReloaded > 0) append(" Zones: $zonesReloaded.")
        if (roomsAdded > 0) append(" Rooms added: $roomsAdded.")
        if (roomsRemoved > 0) append(" Rooms removed: $roomsRemoved.")
        if (mobSpawnsAdded > 0) append(" New mob spawns: $mobSpawnsAdded.")
        if (itemSpawnsAdded > 0) append(" New item spawns: $itemSpawnsAdded.")
        if (abilitiesReloaded > 0) append(" Abilities: $abilitiesReloaded.")
        if (statusEffectsReloaded > 0) append(" Status effects: $statusEffectsReloaded.")
        if (playersRelocated > 0) append(" Players relocated: $playersRelocated.")
        if (errors.isNotEmpty()) append(" Errors: ${errors.joinToString("; ")}.")
    }
}

/**
 * Orchestrates data-only hot reload: re-reads world YAML and config-driven
 * definitions, merges them into the live engine state, and refreshes registries.
 *
 * **Thread safety:** all methods must be called from the single-threaded engine dispatcher.
 * The engine tick loop does NOT pause; reload runs between ticks.
 *
 * **What reloads:** rooms, mob spawn definitions, item templates, shops,
 * dialogue trees, quest definitions, gathering nodes, crafting recipes,
 * ability definitions, status effect definitions.
 *
 * **What stays:** all player sessions, player state, active mobs/combat,
 * engine tick loop. New spawns use updated definitions on next respawn.
 */
class HotReloadManager(
    private val world: World,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val shopRegistry: ShopRegistry,
    private val trainerRegistry: TrainerRegistry,
    private val gatheringRegistry: GatheringRegistry,
    private val craftingRegistry: CraftingRegistry,
    private val questRegistry: QuestRegistry,
    private val abilityRegistry: AbilityRegistry,
    private val statusEffectRegistry: StatusEffectRegistry,
    private val equipmentSlotRegistry: EquipmentSlotRegistry,
    private val mobSystem: MobSystem,
    private val behaviorTreeSystem: BehaviorTreeSystem,
    private val gmcpEmitter: GmcpEmitter,
    private val worldState: WorldStateRegistry,
    private val achievementRegistry: AchievementRegistry,
    private val achievementCategoryRegistry: AchievementCategoryRegistry,
    private val engineConfig: EngineConfig,
    private val imagesBaseUrl: String,
    private val worldLoader: () -> World,
    private val onZoneScheduleRefresh: () -> Unit = {},
) {
    /**
     * Reloads world YAML: rooms, mob spawns, item templates, shops, quests,
     * gathering nodes, and crafting recipes. Active mobs/combat are untouched;
     * new spawns take effect on next respawn.
     */
    suspend fun reloadWorld(): HotReloadResult {
        val freshWorld: World
        try {
            freshWorld = worldLoader()
        } catch (e: Exception) {
            log.error(e) { "Hot reload failed: could not load world" }
            return HotReloadResult(errors = listOf("Failed to load world: ${e.message}"))
        }

        val oldRoomIds = world.rooms.keys.toSet()
        val newRoomIds = freshWorld.rooms.keys.toSet()
        val removedRooms = world.replaceAll(freshWorld)
        val addedRoomIds = newRoomIds - oldRoomIds

        // Propagate start room change so new accounts / recall use the updated room.
        players.startRoom = world.startRoom

        log.info {
            "Hot reload: world replaced. Zones=${freshWorld.zones().size}, " +
                "rooms added=${addedRoomIds.size}, rooms removed=${removedRooms.size}"
        }

        // Relocate players in removed rooms to the start room.
        var playersRelocated = 0
        if (removedRooms.isNotEmpty()) {
            for (player in players.allPlayers()) {
                if (player.roomId in removedRooms) {
                    val destination = world.startRoom
                    players.moveTo(player.sessionId, destination)
                    outbound.send(
                        OutboundEvent.SendText(
                            player.sessionId,
                            "The world shifts around you... you find yourself elsewhere.",
                        ),
                    )
                    outbound.send(OutboundEvent.SendPrompt(player.sessionId))
                    playersRelocated++
                }
            }
        }

        // Refresh registries from updated world data.
        shopRegistry.clear()
        shopRegistry.register(world.shopDefinitions)

        trainerRegistry.clear()
        trainerRegistry.register(world.trainerDefinitions)

        gatheringRegistry.clear()
        gatheringRegistry.register(world.gatheringNodes)

        craftingRegistry.clear()
        craftingRegistry.register(world.recipes)

        questRegistry.clear()
        world.questDefinitions.forEach { questRegistry.register(it) }

        // Reload achievement definitions from classpath (may be shadowed by /app/data overlay).
        achievementRegistry.clear()
        AchievementLoader.loadFromResource("world/achievements.yaml", achievementRegistry, achievementCategoryRegistry)

        // Update item templates (existing items keep old stats; new templates get placed).
        val newItemSpawns = items.updateTemplates(world.itemSpawns)

        // Spawn new mobs that don't already exist in the registry.
        var newMobSpawns = 0
        for (spawn in world.mobSpawns) {
            if (mobs.get(spawn.id) == null) {
                val referenceLevel = highestPlayerLevelInZone(players, spawn.roomId.zone)
                val mobState = spawnToMobState(spawn, world, referenceLevel)
                mobs.upsert(mobState)
                mobSystem.onMobSpawned(spawn.id)
                behaviorTreeSystem.onMobSpawned(spawn.id)
                newMobSpawns++

                // Notify players in the room.
                for (p in players.playersInRoom(spawn.roomId)) {
                    outbound.send(OutboundEvent.SendText(p.sessionId, "${mobState.name} appears."))
                    gmcpEmitter.sendRoomAddMob(p.sessionId, mobState)
                }
            }
        }

        // Rebuild feature lookup maps so new/changed doors, containers, etc. are discoverable.
        worldState.rebuild(world)

        // Sync zone-reset timers with the (possibly changed) lifespan config.
        onZoneScheduleRefresh()

        // Refresh GMCP for all connected players so room data is current.
        for (player in players.allPlayers()) {
            val room = world.rooms[player.roomId] ?: continue
            gmcpEmitter.sendRoomInfo(
                player.sessionId,
                room,
                pvpEnabled = world.isZonePvpEnabled(room.id.zone),
                trainerName = trainerRegistry.trainerInRoom(room.id)?.name,
                lastDeathZone = player.lastDeathZone,
                peek = if (player.autopeekEnabled) buildPeekExits(room, world, worldState).toGmcpPeek() else emptyList(),
            )
            gmcpEmitter.sendRoomMobs(player.sessionId, mobs.mobsInRoom(player.roomId))
            gmcpEmitter.sendRoomItems(player.sessionId, items.itemsInRoom(player.roomId))
            // Refresh staff world browser with updated zones/rooms
            if (player.isStaff) {
                gmcpEmitter.sendStaffWorldInfo(player.sessionId, world)
                gmcpEmitter.sendStaffMobTemplates(player.sessionId, world)
            }
        }

        val result = HotReloadResult(
            zonesReloaded = freshWorld.zones().size,
            roomsAdded = addedRoomIds.size,
            roomsRemoved = removedRooms.size,
            mobSpawnsAdded = newMobSpawns,
            itemSpawnsAdded = newItemSpawns.size,
            playersRelocated = playersRelocated,
        )
        log.info { "Hot reload world: ${result.summary()}" }
        return result
    }

    /**
     * Reloads ability definitions from engine config.
     * Active cooldowns continue; new casts use updated definitions.
     */
    fun reloadAbilities(): HotReloadResult {
        abilityRegistry.clear()
        AbilityRegistryLoader.load(engineConfig.abilities, abilityRegistry, imagesBaseUrl)
        val count = abilityRegistry.all().size
        log.info { "Hot reload abilities: $count definitions loaded" }
        return HotReloadResult(abilitiesReloaded = count)
    }

    /**
     * Reloads status effect definitions from engine config.
     * Active effects continue with their current state; new applications use updated definitions.
     */
    fun reloadStatusEffects(): HotReloadResult {
        statusEffectRegistry.clear()
        StatusEffectRegistryLoader.load(engineConfig.statusEffects, statusEffectRegistry)
        val count = statusEffectRegistry.all().size
        log.info { "Hot reload status effects: $count definitions loaded" }
        return HotReloadResult(statusEffectsReloaded = count)
    }

    /**
     * Reloads equipment slot definitions from engine config and re-sends
     * Char.Equipment.Slots GMCP to all connected players.
     */
    suspend fun reloadEquipmentSlots() {
        equipmentSlotRegistry.reload(engineConfig.equipment)
        val count = equipmentSlotRegistry.all().size
        log.info { "Hot reload equipment slots: $count definitions loaded" }
        for (player in players.allPlayers()) {
            gmcpEmitter.sendEquipmentSlots(player.sessionId)
        }
    }

    /**
     * Reloads everything: world data, abilities, status effects, and equipment slots.
     */
    suspend fun reloadAll(): HotReloadResult {
        val worldResult = reloadWorld()
        val abilityResult = reloadAbilities()
        val effectResult = reloadStatusEffects()
        reloadEquipmentSlots()
        return HotReloadResult(
            zonesReloaded = worldResult.zonesReloaded,
            roomsAdded = worldResult.roomsAdded,
            roomsRemoved = worldResult.roomsRemoved,
            mobSpawnsAdded = worldResult.mobSpawnsAdded,
            itemSpawnsAdded = worldResult.itemSpawnsAdded,
            abilitiesReloaded = abilityResult.abilitiesReloaded,
            statusEffectsReloaded = effectResult.statusEffectsReloaded,
            playersRelocated = worldResult.playersRelocated,
            errors = worldResult.errors + abilityResult.errors + effectResult.errors,
        )
    }
}
