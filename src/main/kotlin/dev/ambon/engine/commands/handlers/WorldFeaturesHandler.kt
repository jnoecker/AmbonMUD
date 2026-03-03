package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.LeverState
import dev.ambon.domain.world.LockableState
import dev.ambon.domain.world.RoomFeature
import dev.ambon.engine.PlayerState
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class WorldFeaturesHandler(
    ctx: EngineContext,
) : CommandHandler {
    private val world = ctx.world
    private val players = ctx.players
    private val items = ctx.items
    private val outbound = ctx.outbound
    private val worldState = ctx.worldState

    override fun register(router: CommandRouter) {
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
    ): Unit = withPlayerAndRoom(sessionId, players, world) { me, room ->
        val feature = requireFeature(sessionId, room, keyword, "open", outbound) ?: return
        val lockable = requireLockable(sessionId, feature, worldState, "open", outbound) ?: return
        when (lockable.state) {
            LockableState.LOCKED -> outbound.send(OutboundEvent.SendError(sessionId, "The ${lockable.displayName} is locked."))
            LockableState.OPEN -> outbound.send(OutboundEvent.SendError(sessionId, "The ${lockable.displayName} is already open."))
            LockableState.CLOSED -> {
                lockable.applyState(LockableState.OPEN)
                outbound.send(OutboundEvent.SendInfo(sessionId, "You open the ${lockable.displayName}."))
                broadcastToRoomExcept(me.roomId, sessionId, "${me.name} opens the ${lockable.displayName}.", players, outbound)
            }
        }
    }

    private suspend fun handleCloseFeature(
        sessionId: SessionId,
        keyword: String,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { me, room ->
        val feature = requireFeature(sessionId, room, keyword, "close", outbound) ?: return
        val lockable = requireLockable(sessionId, feature, worldState, "close", outbound) ?: return
        when (lockable.state) {
            LockableState.OPEN -> {
                lockable.applyState(LockableState.CLOSED)
                outbound.send(OutboundEvent.SendInfo(sessionId, "You close the ${lockable.displayName}."))
                broadcastToRoomExcept(me.roomId, sessionId, "${me.name} closes the ${lockable.displayName}.", players, outbound)
            }
            LockableState.CLOSED -> outbound.send(
                OutboundEvent.SendError(sessionId, "The ${lockable.displayName} is already closed."),
            )
            LockableState.LOCKED -> outbound.send(
                OutboundEvent.SendError(sessionId, "The ${lockable.displayName} is already closed and locked."),
            )
        }
    }

    private suspend fun handleUnlockFeature(
        sessionId: SessionId,
        keyword: String,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { me, room ->
        val feature = requireFeature(sessionId, room, keyword, "unlock", outbound) ?: return
        val lockable = requireLockable(sessionId, feature, worldState, "unlock", outbound) ?: return
        when {
            lockable.state != LockableState.LOCKED ->
                outbound.send(OutboundEvent.SendError(sessionId, "The ${lockable.displayName} is not locked."))
            lockable.keyItemId == null ->
                outbound.send(OutboundEvent.SendError(sessionId, "That doesn't need a key."))
            else -> applyKeyAction(sessionId, me, lockable, LockableState.CLOSED, "unlock", "unlocks")
        }
    }

    private suspend fun handleLockFeature(
        sessionId: SessionId,
        keyword: String,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { me, room ->
        val feature = requireFeature(sessionId, room, keyword, "lock", outbound) ?: return
        val lockable = requireLockable(sessionId, feature, worldState, "lock", outbound) ?: return
        when {
            lockable.state == LockableState.LOCKED ->
                outbound.send(OutboundEvent.SendError(sessionId, "The ${lockable.displayName} is already locked."))
            lockable.state != LockableState.CLOSED ->
                outbound.send(OutboundEvent.SendError(sessionId, "The ${lockable.displayName} must be closed before locking."))
            lockable.keyItemId == null ->
                outbound.send(OutboundEvent.SendError(sessionId, "That doesn't need a key."))
            else -> applyKeyAction(sessionId, me, lockable, LockableState.LOCKED, "lock", "locks")
        }
    }

    private suspend fun handleSearchContainer(
        sessionId: SessionId,
        keyword: String,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { _, room ->
        val feature = findFeatureByKeyword(room, keyword)
        if (feature == null || feature !is RoomFeature.Container) {
            outbound.send(OutboundEvent.SendError(sessionId, "You don't see any container called '$keyword' here."))
            return
        }
        if (!requireContainerOpen(sessionId, feature, worldState, outbound)) return
        val contents = worldState?.getContainerContents(feature.id) ?: emptyList()
        if (contents.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "The ${feature.displayName} is empty."))
        } else {
            val list = contents.map { it.item.displayName }.sorted().joinToString(", ")
            outbound.send(OutboundEvent.SendInfo(sessionId, "In the ${feature.displayName}: $list"))
        }
    }

    private suspend fun handleGetFrom(
        sessionId: SessionId,
        itemKeyword: String,
        containerKeyword: String,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { me, room ->
        val feature = findFeatureByKeyword(room, containerKeyword)
        if (feature == null || feature !is RoomFeature.Container) {
            outbound.send(OutboundEvent.SendError(sessionId, "You don't see any container called '$containerKeyword' here."))
            return
        }
        if (!requireContainerOpen(sessionId, feature, worldState, outbound)) return
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

    private suspend fun handlePutIn(
        sessionId: SessionId,
        itemKeyword: String,
        containerKeyword: String,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { me, room ->
        val feature = findFeatureByKeyword(room, containerKeyword)
        if (feature == null || feature !is RoomFeature.Container) {
            outbound.send(OutboundEvent.SendError(sessionId, "You don't see any container called '$containerKeyword' here."))
            return
        }
        if (!requireContainerOpen(sessionId, feature, worldState, outbound)) return
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

    private suspend fun handlePull(
        sessionId: SessionId,
        keyword: String,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { me, room ->
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

    private suspend fun handleReadSign(
        sessionId: SessionId,
        keyword: String,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { _, room ->
        val feature = findFeatureByKeyword(room, keyword)
        if (feature == null || feature !is RoomFeature.Sign) {
            outbound.send(OutboundEvent.SendError(sessionId, "You don't see anything called '$keyword' to read here."))
            return
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, feature.text))
    }

    private suspend fun applyKeyAction(
        sessionId: SessionId,
        me: PlayerState,
        lockable: Lockable,
        newState: LockableState,
        verb: String,
        verbThirdPerson: String,
    ) {
        val key = findKeyInInventory(sessionId, lockable.keyItemId!!, items)
        if (key == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "You don't have the key for the ${lockable.displayName}."))
        } else {
            lockable.applyState(newState)
            if (lockable.keyConsumed) items.removeFromInventory(sessionId, key.item.keyword)
            outbound.send(OutboundEvent.SendInfo(sessionId, "You $verb the ${lockable.displayName}."))
            broadcastToRoomExcept(
                me.roomId,
                sessionId,
                "${me.name} $verbThirdPerson the ${lockable.displayName}.",
                players,
                outbound,
            )
        }
    }
}
