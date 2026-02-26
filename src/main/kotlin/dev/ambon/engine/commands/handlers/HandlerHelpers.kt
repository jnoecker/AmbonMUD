package dev.ambon.engine.commands.handlers

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.DoorState
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.World
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry

/** Sends a full room description (title, description, exits, items, players, mobs) to [sessionId]. */
internal suspend fun sendLook(
    sessionId: SessionId,
    world: World,
    players: PlayerRegistry,
    mobs: MobRegistry,
    items: ItemRegistry,
    worldState: WorldStateRegistry?,
    outbound: OutboundBus,
    gmcpEmitter: GmcpEmitter?,
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
                if (door != null && ws != null) {
                    val state = ws.getDoorState(door.id)
                    if (state == DoorState.OPEN) label else "$label [${state.name.lowercase()}]"
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

    // Items
    val here = items.itemsInRoom(roomId)
    if (here.isEmpty()) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "Items here: none"))
    } else {
        val list = here.map { it.item.displayName }.sorted().joinToString(", ")
        outbound.send(OutboundEvent.SendInfo(sessionId, "Items here: $list"))
    }

    val roomPlayers =
        players
            .playersInRoom(roomId)
            .map { p ->
                val t = p.activeTitle
                if (t != null) "[$t] ${p.name}" else p.name
            }.sorted()

    val roomMobs =
        mobs
            .mobsInRoom(roomId)
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

    gmcpEmitter?.sendRoomInfo(sessionId, room)
    gmcpEmitter?.sendRoomPlayers(sessionId, players.playersInRoom(roomId).toList())
    gmcpEmitter?.sendRoomMobs(sessionId, mobs.mobsInRoom(roomId))
}

/** Broadcasts [message] to every player in [roomId] except [excludeSessionId]. */
internal suspend fun broadcastToRoomExcept(
    roomId: RoomId,
    excludeSessionId: SessionId,
    message: String,
    players: PlayerRegistry,
    outbound: OutboundBus,
) {
    for (other in players.playersInRoom(roomId)) {
        if (other.sessionId != excludeSessionId) {
            outbound.send(OutboundEvent.SendText(other.sessionId, message))
        }
    }
}

/** Checks if [sessionId] has staff privileges; sends an error and returns false if not. */
internal suspend fun requireStaff(
    sessionId: SessionId,
    players: PlayerRegistry,
    outbound: OutboundBus,
): Boolean {
    val me = players.get(sessionId) ?: return false
    if (!me.isStaff) {
        outbound.send(OutboundEvent.SendError(sessionId, "You are not staff."))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
        return false
    }
    return true
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

/** Returns the zone portion of a namespaced id (before the first ':'). */
internal fun idZone(rawId: String): String = rawId.substringBefore(':', rawId)

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
            runCatching { RoomId("$effectiveZone:$local") }.getOrNull()
        }
    } else {
        runCatching { RoomId("$currentZone:$arg") }.getOrNull()
    }
