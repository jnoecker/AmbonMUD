package dev.ambon.engine.commands.handlers

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.ids.qualifyId
import dev.ambon.domain.puzzle.PuzzleType
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.LockableState
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.QuestSystem
import dev.ambon.engine.TrainerRegistry
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.crafting.GatheringRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry

/**
 * Resolves [sessionId] to a [PlayerState] and executes [block] with it.
 * Returns early from the enclosing function if the player is not found.
 *
 * Because this function is `inline`, the lambda is inlined at the call site,
 * so [block] may call suspend functions and use non-local `return` statements.
 */
internal inline fun PlayerRegistry.withPlayer(
    sessionId: SessionId,
    block: (PlayerState) -> Unit,
) {
    val player = get(sessionId) ?: return
    block(player)
}

/** Sends the full GMCP Char.Items list (inventory + equipment) for [sessionId]. */
internal suspend fun syncItemsGmcp(
    sessionId: SessionId,
    items: ItemRegistry,
    gmcpEmitter: GmcpEmitter?,
) {
    gmcpEmitter?.sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
}

/** Syncs player defense stats and sends the GMCP items list after an equip/unequip change. */
internal suspend fun afterEquipChange(
    sessionId: SessionId,
    combat: CombatSystem,
    items: ItemRegistry,
    gmcpEmitter: GmcpEmitter?,
    markStatsDirty: ((SessionId) -> Unit)? = null,
) {
    combat.syncPlayerDefense(sessionId)
    syncItemsGmcp(sessionId, items, gmcpEmitter)
    markStatsDirty?.invoke(sessionId)
}

/**
 * Finds an online player by [name], or sends "No such player" and returns null.
 * Callers can use non-local return from the enclosing inline [PlayerRegistry.withPlayer].
 */
internal suspend fun requirePlayerOnline(
    sessionId: SessionId,
    name: String,
    players: PlayerRegistry,
    outbound: OutboundBus,
): SessionId? {
    val targetSid = players.findSessionByName(name)
    if (targetSid == null) {
        outbound.send(OutboundEvent.SendError(sessionId, "No such player: $name"))
    }
    return targetSid
}

/** Checks that [target] is in the same room as [me]; sends an error and returns false otherwise. */
internal suspend fun requireSameRoom(
    sessionId: SessionId,
    me: PlayerState,
    target: PlayerState,
    outbound: OutboundBus,
): Boolean {
    if (target.roomId != me.roomId) {
        outbound.send(OutboundEvent.SendError(sessionId, "${target.name} is not here."))
        return false
    }
    return true
}

/**
 * Sends a scoped GMCP UI feedback event via [gmcpEmitter].
 * Replaces per-handler `sendXxxFeedback` wrapper methods that only differed in [scope].
 */
internal suspend fun sendScopedFeedback(
    sessionId: SessionId,
    gmcpEmitter: GmcpEmitter?,
    type: String,
    message: String,
    scope: String,
    code: String? = null,
    command: String? = null,
) {
    gmcpEmitter?.sendUiFeedback(
        sessionId = sessionId,
        type = type,
        message = message,
        code = code,
        scope = scope,
        command = command,
    )
}

/**
 * Sends both a [OutboundEvent.SendError] text message and a scoped GMCP UI feedback event.
 * Consolidates the common dual-output pattern used across many handlers.
 */
internal suspend fun sendErrorWithFeedback(
    sessionId: SessionId,
    outbound: OutboundBus,
    gmcpEmitter: GmcpEmitter?,
    message: String,
    scope: String,
    code: String? = null,
    command: String? = null,
) {
    outbound.send(OutboundEvent.SendError(sessionId, message))
    sendScopedFeedback(sessionId, gmcpEmitter, "error", message, scope, code, command)
}

/** Convenience extension that delegates to the full [sendLook], pulling all dependencies from this context. */
internal suspend fun EngineContext.sendLook(sessionId: SessionId) {
    sendLook(sessionId, world, players, mobs, items, worldState, outbound, gmcpEmitter, gatheringRegistry, questSystem, trainerRegistry)
    emitShopGmcp(sessionId)
    emitBankGmcp(sessionId)
    emitPuzzleGmcp(sessionId)
    emitStylistGmcp(sessionId)
}

/** Emits `Puzzle.List` with the puzzles in the player's current room, or `Puzzle.Close` otherwise. */
internal suspend fun EngineContext.emitPuzzleGmcp(sessionId: SessionId) {
    val emitter = gmcpEmitter ?: return
    val system = puzzleSystem ?: return
    val me = players.get(sessionId) ?: return
    val puzzles = system.puzzlesInRoom(me.roomId)
    if (puzzles.isEmpty()) {
        emitter.sendPuzzleClose(sessionId)
        return
    }
    val payloads = puzzles.map { p ->
        val isRiddle = p.type == PuzzleType.RIDDLE
        GmcpEmitter.PuzzlePayload(
            id = p.id,
            type = if (isRiddle) "riddle" else "sequence",
            question = p.question,
            totalSteps = if (isRiddle) null else p.steps.size,
            currentStep = if (isRiddle) null else system.sequenceStep(sessionId, p.id),
            solved = system.isSolved(sessionId, p.id),
        )
    }
    emitter.sendPuzzleList(sessionId, payloads)
}

/** Emits `Char.Bank` if the player is in a bank room, so the bank panel has data on room entry. */
internal suspend fun EngineContext.emitBankGmcp(sessionId: SessionId) {
    val emitter = gmcpEmitter ?: return
    val me = players.get(sessionId) ?: return
    val room = world.rooms[me.roomId] ?: return
    if (room.bank) {
        emitter.sendBankState(
            sessionId,
            gold = me.bankGold,
            items = me.bankItems,
            maxItems = bankConfig.maxItems,
        )
    }
}

/** Emits `Shop.List` if the player is in a shop room, otherwise emits `Shop.Close`. */
internal suspend fun EngineContext.emitShopGmcp(sessionId: SessionId) {
    val emitter = gmcpEmitter ?: return
    val registry = shopRegistry ?: return
    val me = players.get(sessionId) ?: return
    val shop = registry.shopInRoom(me.roomId)
    if (shop != null) {
        val shopItems = registry.shopItems(shop)
        emitter.sendShopList(
            sessionId,
            shop.name,
            shopItems,
            buyMultiplier = economyConfig.buyMultiplier,
            sellMultiplier = economyConfig.sellMultiplier,
        )
    } else {
        emitter.sendShopClose(sessionId)
    }
}

/** Emits `Char.Stylist` if the player is in a stylist room, otherwise emits `Char.Stylist.Close`. */
internal suspend fun EngineContext.emitStylistGmcp(sessionId: SessionId) {
    val emitter = gmcpEmitter ?: return
    val registry = raceRegistry ?: return
    val me = players.get(sessionId) ?: return
    val room = world.rooms[me.roomId] ?: return
    if (room.stylist) {
        emitter.sendStylistState(
            sessionId = sessionId,
            currentRace = me.race,
            feeGold = stylistConfig.feeGold,
            playerGold = me.gold,
            races = registry.all(),
        )
    } else {
        emitter.sendStylistClose(sessionId)
    }
}

/** Sends a full room description (title, description, exits, items, resources, players, mobs) to [sessionId]. */
internal suspend fun sendLook(
    sessionId: SessionId,
    world: World,
    players: PlayerRegistry,
    mobs: MobRegistry,
    items: ItemRegistry,
    worldState: WorldStateRegistry?,
    outbound: OutboundBus,
    gmcpEmitter: GmcpEmitter?,
    gatheringRegistry: GatheringRegistry? = null,
    questSystem: QuestSystem? = null,
    trainerRegistry: TrainerRegistry? = null,
) {
    val me = players.get(sessionId) ?: return
    val roomId = me.roomId
    val room = world.rooms[roomId] ?: return

    outbound.send(OutboundEvent.SendText(sessionId, room.title))
    outbound.send(OutboundEvent.SendText(sessionId, room.description))

    val ws = worldState
    val exits =
        if (room.exits.isEmpty()) {
            "none"
        } else {
            room.exits.keys.joinToString(", ") { dir ->
                val label = dir.name.lowercase()
                val door = ws?.doorOnExit(roomId, dir)
                if (door != null) {
                    val state = ws.getDoorState(door.id)
                    if (state == LockableState.OPEN) label else "$label [${state.name.lowercase()}]"
                } else {
                    label
                }
            }
        }
    outbound.send(OutboundEvent.SendInfo(sessionId, "Exits: $exits"))

    // Non-door room features
    val roomFeatures = room.features.filter { it !is RoomFeature.Door }
    if (roomFeatures.isNotEmpty()) {
        val featureDesc =
            roomFeatures.joinToString(", ") { feature ->
                when (feature) {
                    is RoomFeature.Container -> {
                        val state = worldState?.getContainerState(feature.id) ?: feature.initialState
                        "${feature.displayName} (${state.name.lowercase()})"
                    }
                    is RoomFeature.Lever -> {
                        val state = worldState?.getLeverState(feature.id) ?: feature.initialState
                        "${feature.displayName} (${state.name.lowercase()})"
                    }
                    is RoomFeature.Sign -> feature.displayName
                    is RoomFeature.Door -> feature.displayName
                }
            }
        outbound.send(OutboundEvent.SendInfo(sessionId, "You notice: $featureDesc"))
    }

    // Crafting station
    if (room.station != null) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "Crafting station: ${stationDisplayName(room.station)}"))
    }

    // Gathering resources
    val nodes = gatheringRegistry?.nodesInRoom(roomId) ?: emptyList()
    if (nodes.isNotEmpty()) {
        val nodeList = nodes.map { it.displayName }.sorted().joinToString(", ")
        outbound.send(OutboundEvent.SendInfo(sessionId, "Resources: $nodeList"))
    }

    // Items
    val here = items.itemsInRoom(roomId)
    if (here.isEmpty()) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "Items here: none"))
    } else {
        val list = here.map { it.item.displayName }.sorted().joinToString(", ")
        outbound.send(OutboundEvent.SendInfo(sessionId, "Items here: $list"))
    }

    val rawRoomPlayers = players.playersInRoom(roomId)
    // Staff players can see invisible players (shown with [I] tag); non-staff cannot
    val visibleFilter: (PlayerState) -> Boolean =
        if (me.isStaff) { _ -> true } else { p -> !p.invisible }
    val roomPlayers =
        rawRoomPlayers
            .filter(visibleFilter)
            .map { p ->
                val t = p.activeTitle
                val tag = if (p.invisible) "[I] " else ""
                if (t != null) "$tag[$t] ${p.name}" else "$tag${p.name}"
            }.sorted()

    val rawRoomMobs = mobs.mobsInRoom(roomId)
    val roomMobs =
        rawRoomMobs
            .map { it.name }
            .sorted()

    outbound.send(
        OutboundEvent.SendInfo(
            sessionId,
            if (roomPlayers.isEmpty()) "Players here: none" else "Players here: ${roomPlayers.joinToString(", ")}",
        ),
    )

    outbound.send(
        OutboundEvent.SendInfo(
            sessionId,
            if (roomMobs.isEmpty()) "You see: nothing" else "You see: ${roomMobs.joinToString(", ")}",
        ),
    )

    // Send zone map when entering a new zone (or on first look)
    val zone = room.id.zone
    if (gmcpEmitter != null && gmcpEmitter.trackZoneChange(sessionId, zone)) {
        val zoneRooms = world.rooms.values.filter { it.id.zone == zone }
        gmcpEmitter.sendZoneMap(sessionId, zone, zoneRooms)
        gmcpEmitter.sendZoneEnvironment(sessionId, gmcpEmitter.buildZoneEnvironmentPayload(zone))
    }

    val isHousing = room.id.zone.startsWith("house_")
    val housingOwner = if (isHousing) room.id.zone.removePrefix("house_") else null
    val isPvpZone = world.isZonePvpEnabled(room.id.zone)
    val trainerHere = trainerRegistry?.trainerInRoom(room.id)
    gmcpEmitter?.sendRoomInfo(
        sessionId,
        room,
        isHousing = isHousing,
        housingOwner = housingOwner,
        pvpEnabled = isPvpZone,
        trainerName = trainerHere?.name,
        lastDeathZone = me.lastDeathZone,
    )
    gmcpEmitter?.sendRoomPlayers(sessionId, rawRoomPlayers)
    gmcpEmitter?.sendRoomMobs(sessionId, rawRoomMobs)
    val mobIds = rawRoomMobs.map { it.id.value }
    val questAvailable = questSystem?.questAvailableMobIds(sessionId, mobIds) ?: emptySet()
    val questComplete = questSystem?.questCompleteMobIds(sessionId, mobIds) ?: emptySet()
    gmcpEmitter?.sendRoomMobInfo(
        sessionId,
        gmcpEmitter.buildMobInfoEntries(
            rawRoomMobs,
            questAvailableMobIds = questAvailable,
            questCompleteMobIds = questComplete,
        ),
    )
    gmcpEmitter?.sendRoomItems(sessionId, here)

    // Send gathering nodes in this room
    val roomNodes = gatheringRegistry?.nodesInRoom(roomId) ?: emptyList()
    if (roomNodes.isNotEmpty()) {
        gmcpEmitter?.sendCraftingNodes(
            sessionId,
            roomNodes.map { node ->
                GmcpEmitter.CraftingNodePayload(
                    id = node.id,
                    name = node.displayName,
                    skill = node.skill,
                    skillRequired = node.skillRequired,
                    image = node.image,
                )
            },
        )
    } else {
        gmcpEmitter?.sendCraftingNodes(sessionId, emptyList())
    }

    // Send interactive room features (doors, containers, levers, signs).
    // Always send — even when empty — so the client clears features from the previous room.
    gmcpEmitter?.sendRoomFeatures(
        sessionId,
        room.features.map { feature ->
            buildFeaturePayload(feature, worldState)
        },
    )
}

internal fun buildFeaturePayload(
    feature: RoomFeature,
    worldState: WorldStateRegistry?,
): GmcpEmitter.RoomFeaturePayload = when (feature) {
    is RoomFeature.Door -> {
        val state = worldState?.getDoorState(feature.id) ?: feature.initialState
        GmcpEmitter.RoomFeaturePayload(
            id = feature.id,
            name = feature.displayName,
            keyword = feature.keyword,
            type = "door",
            state = state.name.lowercase(),
            direction = feature.direction.name.lowercase(),
            locked = state == LockableState.LOCKED,
            keyRequired = feature.keyItemId != null,
        )
    }
    is RoomFeature.Container -> {
        val state = worldState?.getContainerState(feature.id) ?: feature.initialState
        GmcpEmitter.RoomFeaturePayload(
            id = feature.id,
            name = feature.displayName,
            keyword = feature.keyword,
            type = "container",
            state = state.name.lowercase(),
            locked = state == LockableState.LOCKED,
            keyRequired = feature.keyItemId != null,
        )
    }
    is RoomFeature.Lever -> {
        val state = worldState?.getLeverState(feature.id) ?: feature.initialState
        GmcpEmitter.RoomFeaturePayload(
            id = feature.id,
            name = feature.displayName,
            keyword = feature.keyword,
            type = "lever",
            state = state.name.lowercase(),
        )
    }
    is RoomFeature.Sign -> GmcpEmitter.RoomFeaturePayload(
        id = feature.id,
        name = feature.displayName,
        keyword = feature.keyword,
        type = "sign",
        text = feature.text,
    )
}

/**
 * Convenience extension on [EngineContext] that delegates to [movePlayerWithNotify],
 * pulling [players], [outbound], and [gmcpEmitter] from the context.
 */
internal suspend fun EngineContext.movePlayer(
    sessionId: SessionId,
    from: RoomId,
    to: RoomId,
    departMsg: String,
    arriveMsg: String,
    dialogueSystem: dev.ambon.engine.dialogue.DialogueSystem? = null,
) = movePlayerWithNotify(sessionId, from, to, departMsg, arriveMsg, players, outbound, gmcpEmitter, dialogueSystem)

/**
 * Convenience extension on [EngineContext] that syncs item GMCP state,
 * pulling [items] and [gmcpEmitter] from the context.
 */
internal suspend fun EngineContext.syncItems(sessionId: SessionId) =
    syncItemsGmcp(sessionId, items, gmcpEmitter)

/** Returns a human-readable display name for a crafting station type ID. */
private fun stationDisplayName(station: String): String =
    station.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

/** Broadcasts [message] to every player in [roomId] except [excludeSessionId]. Delegates to [dev.ambon.engine.broadcastToRoom]. */
internal suspend fun broadcastToRoomExcept(
    roomId: RoomId,
    excludeSessionId: SessionId,
    message: String,
    players: PlayerRegistry,
    outbound: OutboundBus,
) = dev.ambon.engine.broadcastToRoom(players, outbound, roomId, message, excludeSessionId)

/** Broadcasts [message] to every player in [roomId], including the sender. Delegates to [dev.ambon.engine.broadcastToRoom]. */
internal suspend fun broadcastToRoom(
    roomId: RoomId,
    message: String,
    players: PlayerRegistry,
    outbound: OutboundBus,
) = dev.ambon.engine.broadcastToRoom(players, outbound, roomId, message)

/** Broadcasts [message] to all provided [memberSessionIds], useful for group chat. */
internal suspend fun broadcastToGroup(
    memberSessionIds: Iterable<SessionId>,
    message: String,
    outbound: OutboundBus,
) {
    for (sid in memberSessionIds) {
        outbound.send(OutboundEvent.SendText(sid, message))
    }
}

/**
 * Sends a [OutboundEvent.SendError] to [sessionId] if [error] is non-null; no-op otherwise.
 *
 * Reduces the repeated `if (err != null) { outbound.send(SendError(sessionId, err)) }` pattern
 * at system call sites to a single expression.
 */
internal suspend fun OutboundBus.sendIfError(sessionId: SessionId, error: String?) {
    if (error != null) send(OutboundEvent.SendError(sessionId, error))
}

/**
 * Type-safe guard for an optional system. Returns the system instance if non-null,
 * or sends an error and returns null.
 *
 * Usage: `val gs = requireSystemOrNull(sessionId, groupSystem, "Groups", outbound) ?: return`
 */
internal suspend fun <T> requireSystemOrNull(
    sessionId: SessionId,
    system: T?,
    name: String,
    outbound: OutboundBus,
): T? {
    if (system == null) {
        outbound.send(OutboundEvent.SendError(sessionId, "$name are not available on this server."))
    }
    return system
}

/**
 * Notifies old-room players of departure, moves the player, then notifies new-room players of arrival.
 * Also fires [dialogueSystem] movement callbacks and emits GMCP room-player add/remove.
 */
internal suspend fun movePlayerWithNotify(
    sessionId: SessionId,
    from: RoomId,
    to: RoomId,
    departMsg: String,
    arriveMsg: String,
    players: PlayerRegistry,
    outbound: OutboundBus,
    gmcpEmitter: GmcpEmitter?,
    dialogueSystem: dev.ambon.engine.dialogue.DialogueSystem? = null,
) {
    val me = players.get(sessionId) ?: return
    if (!me.invisible) {
        for (other in players.playersInRoom(from).filter { it.sessionId != me.sessionId }) {
            outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} $departMsg"))
            gmcpEmitter?.sendRoomRemovePlayer(other.sessionId, me.name)
        }
    }
    dialogueSystem?.onPlayerMoved(sessionId)
    players.moveTo(sessionId, to)
    if (!me.invisible) {
        for (other in players.playersInRoom(to).filter { it.sessionId != me.sessionId }) {
            outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} $arriveMsg"))
            gmcpEmitter?.sendRoomAddPlayer(other.sessionId, me)
        }
    }
}

/** Returns the single-letter direction abbreviation (n/s/e/w/u/d). */
internal fun dirAbbrev(dir: Direction): String =
    when (dir) {
        Direction.NORTH -> "n"
        Direction.SOUTH -> "s"
        Direction.EAST -> "e"
        Direction.WEST -> "w"
        Direction.UP -> "u"
        Direction.DOWN -> "d"
    }

/**
 * Finds a room feature matching [keyword] by exact keyword, direction name/abbreviation (for doors),
 * or substring of displayName (≥3 chars).
 */
internal fun findFeatureByKeyword(
    room: Room,
    keyword: String,
): RoomFeature? {
    val lower = keyword.lowercase()
    room.features.firstOrNull { it.keyword.lowercase() == lower }?.let { return it }
    room.features.filterIsInstance<RoomFeature.Door>().firstOrNull {
        it.direction.name.lowercase() == lower || dirAbbrev(it.direction) == lower
    }?.let { return it }
    if (lower.length >= 3) {
        room.features.firstOrNull { it.displayName.lowercase().contains(lower) }?.let { return it }
    }
    return null
}

/** Finds a key item matching [keyItemId] in a player's inventory. */
internal fun findKeyInInventory(
    sessionId: SessionId,
    keyItemId: ItemId,
    items: ItemRegistry,
) = items.inventory(sessionId).firstOrNull { it.id == keyItemId }

/** Formats a base stat with optional equipment bonus, e.g. "12 (+3)". */
internal fun formatStat(
    base: Int,
    equipBonus: Int,
): String = if (equipBonus > 0) "$base (+$equipBonus)" else "$base"

/** Produces an "Exits: n, s, e" line for a room. */
internal fun exitsLine(r: Room): String =
    if (r.exits.isEmpty()) {
        "Exits: none"
    } else {
        val names =
            r.exits.keys
                .sortedBy { it.name }
                .joinToString(", ") { it.name.lowercase() }
        "Exits: $names"
    }

/**
 * Resolves [sessionId] to a [PlayerState] and its current [Room], then executes [block].
 * Returns early from the enclosing function if the player is not found or the room is missing.
 */
internal inline fun withPlayerAndRoom(
    sessionId: SessionId,
    players: PlayerRegistry,
    world: World,
    block: (PlayerState, Room) -> Unit,
) {
    val me = players.get(sessionId) ?: return
    val room = world.rooms[me.roomId] ?: return
    block(me, room)
}

/**
 * Finds a [RoomFeature] by [keyword] in [room], or sends an error with the given [verb] and returns null.
 * Example error: "You don't see any 'chest' to open here."
 */
internal suspend fun requireFeature(
    sessionId: SessionId,
    room: Room,
    keyword: String,
    verb: String,
    outbound: OutboundBus,
): RoomFeature? {
    val feature = findFeatureByKeyword(room, keyword)
    if (feature == null) {
        outbound.send(OutboundEvent.SendError(sessionId, "You don't see any '$keyword' to $verb here."))
    }
    return feature
}

/**
 * Resolves a [Lockable] from [feature], or sends "You can't [verb] that." and returns null.
 */
internal suspend fun requireLockable(
    sessionId: SessionId,
    feature: RoomFeature,
    worldState: WorldStateRegistry?,
    verb: String,
    outbound: OutboundBus,
): Lockable? {
    val lockable = resolveLockable(feature, worldState)
    if (lockable == null) {
        outbound.send(OutboundEvent.SendError(sessionId, "You can't $verb that."))
    }
    return lockable
}

/**
 * Checks that a [RoomFeature.Container] is open; sends "The <name> is not open." and returns false otherwise.
 */
internal suspend fun requireContainerOpen(
    sessionId: SessionId,
    feature: RoomFeature.Container,
    worldState: WorldStateRegistry?,
    outbound: OutboundBus,
): Boolean {
    val state = worldState?.getContainerState(feature.id) ?: feature.initialState
    if (state != LockableState.OPEN) {
        outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} is not open."))
        return false
    }
    return true
}

/**
 * Finds a [RoomFeature.Container] by keyword, verifies it's open, and returns it.
 * Sends an appropriate error and returns null if not found, not a container, or not open.
 */
internal suspend fun requireOpenContainer(
    sessionId: SessionId,
    room: Room,
    keyword: String,
    worldState: WorldStateRegistry?,
    outbound: OutboundBus,
): RoomFeature.Container? {
    val feature = findFeatureByKeyword(room, keyword)
    if (feature == null || feature !is RoomFeature.Container) {
        outbound.send(OutboundEvent.SendError(sessionId, "You don't see any container called '$keyword' here."))
        return null
    }
    if (!requireContainerOpen(sessionId, feature, worldState, outbound)) return null
    return feature
}

/** Adapter providing uniform access to a Door or Container's lockable state. */
internal class Lockable(
    val displayName: String,
    val state: LockableState,
    val keyItemId: ItemId?,
    val keyConsumed: Boolean,
    val isDoor: Boolean,
    val applyState: (LockableState) -> Unit,
)

/** Creates a [Lockable] adapter from a Door or Container; returns null for other feature types. */
internal fun resolveLockable(
    feature: RoomFeature,
    worldState: WorldStateRegistry?,
): Lockable? =
    when (feature) {
        is RoomFeature.Door -> Lockable(
            displayName = feature.displayName,
            state = worldState?.getDoorState(feature.id) ?: feature.initialState,
            keyItemId = feature.keyItemId,
            keyConsumed = feature.keyConsumed,
            isDoor = true,
            applyState = { worldState?.setDoorState(feature.id, it) },
        )
        is RoomFeature.Container -> Lockable(
            displayName = feature.displayName,
            state = worldState?.getContainerState(feature.id) ?: feature.initialState,
            keyItemId = feature.keyItemId,
            keyConsumed = feature.keyConsumed,
            isDoor = false,
            applyState = { worldState?.setContainerState(feature.id, it) },
        )
        else -> null
    }

/**
 * Attempts a cross-zone move to [targetRoomId]. If [onCrossZoneMove] is available,
 * invokes the callback and then suppresses auto-prompt, returning true.
 * Otherwise returns false (caller should send a fallback error).
 *
 * Auto-prompt is suppressed *after* the callback completes so that if the
 * callback throws, the router's default prompt delivery still fires.
 */
internal suspend fun attemptCrossZoneMove(
    sessionId: SessionId,
    targetRoomId: RoomId,
    onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)?,
    suppressAutoPrompt: () -> Unit,
): Boolean {
    if (onCrossZoneMove != null) {
        onCrossZoneMove.invoke(sessionId, targetRoomId)
        suppressAutoPrompt()
        return true
    }
    return false
}

/** Resolves a goto/transfer argument to a [RoomId], handling "zone:room", "room", "zone:". */
internal fun resolveGotoArg(
    arg: String,
    currentZone: String,
    world: World,
): RoomId? =
    if (':' in arg) {
        val zone = arg.substringBefore(':').trim()
        val local = arg.substringAfter(':').trim()
        val effectiveZone = zone.ifEmpty { currentZone }
        if (local.isEmpty()) {
            if (world.startRoom.zone == effectiveZone) {
                world.startRoom
            } else {
                world.rooms.keys.firstOrNull { it.zone == effectiveZone }
            }
        } else {
            runCatching { RoomId(qualifyId(effectiveZone, local)) }.getOrNull()
        }
    } else {
        runCatching { RoomId(qualifyId(currentZone, arg)) }.getOrNull()
    }
