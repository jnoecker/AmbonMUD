package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.ContainerState
import dev.ambon.domain.world.DoorState
import dev.ambon.domain.world.LeverState
import dev.ambon.domain.world.RoomFeature
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class WorldFeaturesHandler(
    router: CommandRouter,
    ctx: EngineContext,
) {
    private val world = ctx.world
    private val players = ctx.players
    private val items = ctx.items
    private val outbound = ctx.outbound
    private val worldState = ctx.worldState

    init {
        router.on<Command.OpenFeature> { sid, cmd -> handleOpenFeature(sid, cmd.keyword) }
        router.on<Command.CloseFeature> { sid, cmd -> handleCloseFeature(sid, cmd.keyword) }
        router.on<Command.UnlockFeature> { sid, cmd -> handleUnlockFeature(sid, cmd.keyword) }
        router.on<Command.LockFeature> { sid, cmd -> handleLockFeature(sid, cmd.keyword) }
        router.on<Command.SearchContainer> { sid, cmd -> handleSearchContainer(sid, cmd.keyword) }
        router.on<Command.GetFrom> { sid, cmd -> handleGetFrom(sid, cmd.itemKeyword, cmd.containerKeyword) }
        router.on<Command.PutIn> { sid, cmd -> handlePutIn(sid, cmd.itemKeyword, cmd.containerKeyword) }
        router.on<Command.Pull> { sid, cmd -> handlePull(sid, cmd.keyword) }
        router.on<Command.ReadSign> { sid, cmd -> handleReadSign(sid, cmd.keyword) }
    }

    private suspend fun handleOpenFeature(
        sessionId: SessionId,
        keyword: String,
    ) {
        players.withPlayer(sessionId) { me ->
            val room = world.rooms[me.roomId] ?: return
            val feature = findFeatureByKeyword(room, keyword)
            if (feature == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't see any '$keyword' to open here."))
                return
            }
            when (feature) {
                is RoomFeature.Door -> {
                    val state = worldState?.getDoorState(feature.id) ?: feature.initialState
                    when (state) {
                        DoorState.LOCKED -> outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} is locked."))
                        DoorState.OPEN -> outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} is already open."))
                        DoorState.CLOSED -> {
                            worldState?.setDoorState(feature.id, DoorState.OPEN)
                            outbound.send(OutboundEvent.SendInfo(sessionId, "You open the ${feature.displayName}."))
                            broadcastToRoomExcept(me.roomId, sessionId, "${me.name} opens the ${feature.displayName}.", players, outbound)
                        }
                    }
                }
                is RoomFeature.Container -> {
                    val state = worldState?.getContainerState(feature.id) ?: feature.initialState
                    when (state) {
                        ContainerState.LOCKED -> outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} is locked."))
                        ContainerState.OPEN -> outbound.send(
                            OutboundEvent.SendError(sessionId, "The ${feature.displayName} is already open."),
                        )
                        ContainerState.CLOSED -> {
                            worldState?.setContainerState(feature.id, ContainerState.OPEN)
                            outbound.send(OutboundEvent.SendInfo(sessionId, "You open the ${feature.displayName}."))
                            broadcastToRoomExcept(me.roomId, sessionId, "${me.name} opens the ${feature.displayName}.", players, outbound)
                        }
                    }
                }
                else -> outbound.send(OutboundEvent.SendError(sessionId, "You can't open that."))
            }
        }
    }

    private suspend fun handleCloseFeature(
        sessionId: SessionId,
        keyword: String,
    ) {
        players.withPlayer(sessionId) { me ->
            val room = world.rooms[me.roomId] ?: return
            val feature = findFeatureByKeyword(room, keyword)
            if (feature == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't see any '$keyword' to close here."))
                return
            }
            when (feature) {
                is RoomFeature.Door -> {
                    val state = worldState?.getDoorState(feature.id) ?: feature.initialState
                    when (state) {
                        DoorState.OPEN -> {
                            worldState?.setDoorState(feature.id, DoorState.CLOSED)
                            outbound.send(OutboundEvent.SendInfo(sessionId, "You close the ${feature.displayName}."))
                            broadcastToRoomExcept(me.roomId, sessionId, "${me.name} closes the ${feature.displayName}.", players, outbound)
                        }
                        DoorState.CLOSED -> outbound.send(
                            OutboundEvent.SendError(sessionId, "The ${feature.displayName} is already closed."),
                        )
                        DoorState.LOCKED -> outbound.send(
                            OutboundEvent.SendError(sessionId, "The ${feature.displayName} is already closed and locked."),
                        )
                    }
                }
                is RoomFeature.Container -> {
                    val state = worldState?.getContainerState(feature.id) ?: feature.initialState
                    when (state) {
                        ContainerState.OPEN -> {
                            worldState?.setContainerState(feature.id, ContainerState.CLOSED)
                            outbound.send(OutboundEvent.SendInfo(sessionId, "You close the ${feature.displayName}."))
                            broadcastToRoomExcept(me.roomId, sessionId, "${me.name} closes the ${feature.displayName}.", players, outbound)
                        }
                        ContainerState.CLOSED -> outbound.send(
                            OutboundEvent.SendError(sessionId, "The ${feature.displayName} is already closed."),
                        )
                        ContainerState.LOCKED -> outbound.send(
                            OutboundEvent.SendError(sessionId, "The ${feature.displayName} is already closed and locked."),
                        )
                    }
                }
                else -> outbound.send(OutboundEvent.SendError(sessionId, "You can't close that."))
            }
        }
    }

    private suspend fun handleUnlockFeature(
        sessionId: SessionId,
        keyword: String,
    ) {
        players.withPlayer(sessionId) { me ->
            val room = world.rooms[me.roomId] ?: return
            val feature = findFeatureByKeyword(room, keyword)
            if (feature == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't see any '$keyword' to unlock here."))
                return
            }
            when (feature) {
                is RoomFeature.Door -> {
                    val state = worldState?.getDoorState(feature.id) ?: feature.initialState
                    when {
                        state != DoorState.LOCKED ->
                            outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} is not locked."))
                        feature.keyItemId == null ->
                            outbound.send(OutboundEvent.SendError(sessionId, "That doesn't need a key."))
                        else -> {
                            val key = findKeyInInventory(sessionId, feature.keyItemId, items)
                            if (key == null) {
                                outbound.send(OutboundEvent.SendError(sessionId, "You don't have the key for the ${feature.displayName}."))
                            } else {
                                worldState?.setDoorState(feature.id, DoorState.CLOSED)
                                if (feature.keyConsumed) items.removeFromInventory(sessionId, key.item.keyword)
                                outbound.send(OutboundEvent.SendInfo(sessionId, "You unlock the ${feature.displayName}."))
                                broadcastToRoomExcept(
                                    me.roomId,
                                    sessionId,
                                    "${me.name} unlocks the ${feature.displayName}.",
                                    players,
                                    outbound,
                                )
                            }
                        }
                    }
                }
                is RoomFeature.Container -> {
                    val state = worldState?.getContainerState(feature.id) ?: feature.initialState
                    when {
                        state != ContainerState.LOCKED ->
                            outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} is not locked."))
                        feature.keyItemId == null ->
                            outbound.send(OutboundEvent.SendError(sessionId, "That doesn't need a key."))
                        else -> {
                            val key = findKeyInInventory(sessionId, feature.keyItemId, items)
                            if (key == null) {
                                outbound.send(OutboundEvent.SendError(sessionId, "You don't have the key for the ${feature.displayName}."))
                            } else {
                                worldState?.setContainerState(feature.id, ContainerState.CLOSED)
                                if (feature.keyConsumed) items.removeFromInventory(sessionId, key.item.keyword)
                                outbound.send(OutboundEvent.SendInfo(sessionId, "You unlock the ${feature.displayName}."))
                                broadcastToRoomExcept(
                                    me.roomId,
                                    sessionId,
                                    "${me.name} unlocks the ${feature.displayName}.",
                                    players,
                                    outbound,
                                )
                            }
                        }
                    }
                }
                else -> outbound.send(OutboundEvent.SendError(sessionId, "You can't unlock that."))
            }
        }
    }

    private suspend fun handleLockFeature(
        sessionId: SessionId,
        keyword: String,
    ) {
        players.withPlayer(sessionId) { me ->
            val room = world.rooms[me.roomId] ?: return
            val feature = findFeatureByKeyword(room, keyword)
            if (feature == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't see any '$keyword' to lock here."))
                return
            }
            when (feature) {
                is RoomFeature.Door -> {
                    val state = worldState?.getDoorState(feature.id) ?: feature.initialState
                    when {
                        state == DoorState.LOCKED ->
                            outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} is already locked."))
                        state != DoorState.CLOSED ->
                            outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} must be closed before locking."))
                        feature.keyItemId == null ->
                            outbound.send(OutboundEvent.SendError(sessionId, "That doesn't need a key."))
                        else -> {
                            val key = findKeyInInventory(sessionId, feature.keyItemId, items)
                            if (key == null) {
                                outbound.send(OutboundEvent.SendError(sessionId, "You don't have the key for the ${feature.displayName}."))
                            } else {
                                worldState?.setDoorState(feature.id, DoorState.LOCKED)
                                if (feature.keyConsumed) items.removeFromInventory(sessionId, key.item.keyword)
                                outbound.send(OutboundEvent.SendInfo(sessionId, "You lock the ${feature.displayName}."))
                                broadcastToRoomExcept(
                                    me.roomId,
                                    sessionId,
                                    "${me.name} locks the ${feature.displayName}.",
                                    players,
                                    outbound,
                                )
                            }
                        }
                    }
                }
                is RoomFeature.Container -> {
                    val state = worldState?.getContainerState(feature.id) ?: feature.initialState
                    when {
                        state == ContainerState.LOCKED ->
                            outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} is already locked."))
                        state != ContainerState.CLOSED ->
                            outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} must be closed before locking."))
                        feature.keyItemId == null ->
                            outbound.send(OutboundEvent.SendError(sessionId, "That doesn't need a key."))
                        else -> {
                            val key = findKeyInInventory(sessionId, feature.keyItemId, items)
                            if (key == null) {
                                outbound.send(OutboundEvent.SendError(sessionId, "You don't have the key for the ${feature.displayName}."))
                            } else {
                                worldState?.setContainerState(feature.id, ContainerState.LOCKED)
                                if (feature.keyConsumed) items.removeFromInventory(sessionId, key.item.keyword)
                                outbound.send(OutboundEvent.SendInfo(sessionId, "You lock the ${feature.displayName}."))
                                broadcastToRoomExcept(
                                    me.roomId,
                                    sessionId,
                                    "${me.name} locks the ${feature.displayName}.",
                                    players,
                                    outbound,
                                )
                            }
                        }
                    }
                }
                else -> outbound.send(OutboundEvent.SendError(sessionId, "You can't lock that."))
            }
        }
    }

    private suspend fun handleSearchContainer(
        sessionId: SessionId,
        keyword: String,
    ) {
        players.withPlayer(sessionId) { me ->
            val room = world.rooms[me.roomId] ?: return
            val feature = findFeatureByKeyword(room, keyword)
            if (feature == null || feature !is RoomFeature.Container) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't see any container called '$keyword' here."))
                return
            }
            val state = worldState?.getContainerState(feature.id) ?: feature.initialState
            if (state != ContainerState.OPEN) {
                outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} is not open."))
                return
            }
            val contents = worldState?.getContainerContents(feature.id) ?: emptyList()
            if (contents.isEmpty()) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "The ${feature.displayName} is empty."))
            } else {
                val list = contents.map { it.item.displayName }.sorted().joinToString(", ")
                outbound.send(OutboundEvent.SendInfo(sessionId, "In the ${feature.displayName}: $list"))
            }
        }
    }

    private suspend fun handleGetFrom(
        sessionId: SessionId,
        itemKeyword: String,
        containerKeyword: String,
    ) {
        players.withPlayer(sessionId) { me ->
            val room = world.rooms[me.roomId] ?: return
            val feature = findFeatureByKeyword(room, containerKeyword)
            if (feature == null || feature !is RoomFeature.Container) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't see any container called '$containerKeyword' here."))
                return
            }
            val state = worldState?.getContainerState(feature.id) ?: feature.initialState
            if (state != ContainerState.OPEN) {
                outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} is not open."))
                return
            }
            val item = worldState?.removeFromContainer(feature.id, itemKeyword)
            if (item == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "There is no '$itemKeyword' in the ${feature.displayName}."))
            } else {
                items.addToInventory(sessionId, item)
                outbound.send(OutboundEvent.SendInfo(sessionId, "You take ${item.item.displayName} from the ${feature.displayName}."))
                broadcastToRoomExcept(
                    me.roomId,
                    sessionId,
                    "${me.name} takes ${item.item.displayName} from the ${feature.displayName}.",
                    players,
                    outbound,
                )
            }
        }
    }

    private suspend fun handlePutIn(
        sessionId: SessionId,
        itemKeyword: String,
        containerKeyword: String,
    ) {
        players.withPlayer(sessionId) { me ->
            val room = world.rooms[me.roomId] ?: return
            val feature = findFeatureByKeyword(room, containerKeyword)
            if (feature == null || feature !is RoomFeature.Container) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't see any container called '$containerKeyword' here."))
                return
            }
            val state = worldState?.getContainerState(feature.id) ?: feature.initialState
            if (state != ContainerState.OPEN) {
                outbound.send(OutboundEvent.SendError(sessionId, "The ${feature.displayName} is not open."))
                return
            }
            val item = items.removeFromInventory(sessionId, itemKeyword)
            if (item == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't have any '$itemKeyword'."))
            } else {
                worldState?.addToContainer(feature.id, item)
                outbound.send(OutboundEvent.SendInfo(sessionId, "You put ${item.item.displayName} in the ${feature.displayName}."))
                broadcastToRoomExcept(
                    me.roomId,
                    sessionId,
                    "${me.name} puts ${item.item.displayName} in the ${feature.displayName}.",
                    players,
                    outbound,
                )
            }
        }
    }

    private suspend fun handlePull(
        sessionId: SessionId,
        keyword: String,
    ) {
        players.withPlayer(sessionId) { me ->
            val room = world.rooms[me.roomId] ?: return
            val feature = findFeatureByKeyword(room, keyword)
            if (feature == null || feature !is RoomFeature.Lever) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't see any lever called '$keyword' here."))
                return
            }
            val state = worldState?.getLeverState(feature.id) ?: feature.initialState
            val newState = if (state == LeverState.UP) LeverState.DOWN else LeverState.UP
            worldState?.setLeverState(feature.id, newState)
            outbound.send(OutboundEvent.SendInfo(sessionId, "You pull the ${feature.displayName}. It moves ${newState.name.lowercase()}."))
            broadcastToRoomExcept(me.roomId, sessionId, "${me.name} pulls the ${feature.displayName}.", players, outbound)
        }
    }

    private suspend fun handleReadSign(
        sessionId: SessionId,
        keyword: String,
    ) {
        players.withPlayer(sessionId) { me ->
            val room = world.rooms[me.roomId] ?: return
            val feature = findFeatureByKeyword(room, keyword)
            if (feature == null || feature !is RoomFeature.Sign) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't see anything called '$keyword' to read here."))
                return
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, feature.text))
        }
    }
}
