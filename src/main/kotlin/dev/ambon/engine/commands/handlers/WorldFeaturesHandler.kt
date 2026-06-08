package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.LeverState
import dev.ambon.domain.world.LockableState
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.RoomFeature
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.PlayerState
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class WorldFeaturesHandler(
    ctx: EngineContext,
    private val puzzleHandler: PuzzleHandler? = null,
) : CommandHandler {
    private val world = ctx.world
    private val players = ctx.players
    private val items = ctx.items
    private val outbound = ctx.outbound
    private val worldState = ctx.worldState
    private val gmcpEmitter = ctx.gmcpEmitter

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

    /** Resolves the player, room, feature, and lockable; calls [block] if all are found. */
    private suspend inline fun withLockable(
        sessionId: SessionId,
        keyword: String,
        verb: String,
        block: (PlayerState, Lockable) -> Unit,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { me, room ->
        val feature = requireFeature(sessionId, room, keyword, verb, outbound) ?: return
        val lockable = requireLockable(sessionId, feature, worldState, verb, outbound) ?: return
        block(me, lockable)
    }

    private suspend fun handleOpenFeature(
        sessionId: SessionId,
        keyword: String,
    ): Unit = withLockable(sessionId, keyword, "open") { me, lockable ->
        when (lockable.state) {
            LockableState.LOCKED -> outbound.send(OutboundEvent.SendError(sessionId, "${theCap(lockable.displayName)} is locked."))
            LockableState.OPEN -> outbound.send(OutboundEvent.SendError(sessionId, "${theCap(lockable.displayName)} is already open."))
            LockableState.CLOSED -> {
                lockable.applyState(LockableState.OPEN)
                val msg = "You open ${the(lockable.displayName)}."
                outbound.send(OutboundEvent.SendInfo(sessionId, msg))
                gmcpEmitter?.sendUiFeedback(sessionId, "success", msg, scope = "features", command = "open")
                broadcastToRoomExcept(me.roomId, sessionId, "${me.name} opens ${the(lockable.displayName)}.", players, outbound)
                world.rooms[me.roomId]?.let { emitRoomFeatures(it) }
            }
        }
    }

    private suspend fun handleCloseFeature(
        sessionId: SessionId,
        keyword: String,
    ): Unit = withLockable(sessionId, keyword, "close") { me, lockable ->
        when (lockable.state) {
            LockableState.OPEN -> {
                lockable.applyState(LockableState.CLOSED)
                val msg = "You close ${the(lockable.displayName)}."
                outbound.send(OutboundEvent.SendInfo(sessionId, msg))
                gmcpEmitter?.sendUiFeedback(sessionId, "success", msg, scope = "features", command = "close")
                broadcastToRoomExcept(me.roomId, sessionId, "${me.name} closes ${the(lockable.displayName)}.", players, outbound)
                world.rooms[me.roomId]?.let { emitRoomFeatures(it) }
            }
            LockableState.CLOSED -> outbound.send(
                OutboundEvent.SendError(sessionId, "${theCap(lockable.displayName)} is already closed."),
            )
            LockableState.LOCKED -> outbound.send(
                OutboundEvent.SendError(sessionId, "${theCap(lockable.displayName)} is already closed and locked."),
            )
        }
    }

    private suspend fun handleUnlockFeature(
        sessionId: SessionId,
        keyword: String,
    ): Unit = withLockable(sessionId, keyword, "unlock") { me, lockable ->
        when {
            lockable.state != LockableState.LOCKED ->
                outbound.send(OutboundEvent.SendError(sessionId, "${theCap(lockable.displayName)} is not locked."))
            lockable.keyItemId == null ->
                outbound.send(OutboundEvent.SendError(sessionId, "That doesn't need a key."))
            else -> {
                // Doors: unlock also opens them (one player action, not two) so that puzzle
                // keys, manual `unlock <door>`, and canvas "Unlock" button all resolve to a
                // passable door. Containers still unlock to CLOSED so players can inspect
                // contents separately.
                val targetState =
                    if (lockable.isDoor) LockableState.OPEN else LockableState.CLOSED
                val verbThirdPerson = if (lockable.isDoor) "unlocks and opens" else "unlocks"
                val verb = if (lockable.isDoor) "unlock and open" else "unlock"
                applyKeyAction(sessionId, me, lockable, targetState, verb, verbThirdPerson)
            }
        }
    }

    private suspend fun handleLockFeature(
        sessionId: SessionId,
        keyword: String,
    ): Unit = withLockable(sessionId, keyword, "lock") { me, lockable ->
        when {
            lockable.state == LockableState.LOCKED ->
                outbound.send(OutboundEvent.SendError(sessionId, "${theCap(lockable.displayName)} is already locked."))
            lockable.state != LockableState.CLOSED ->
                outbound.send(OutboundEvent.SendError(sessionId, "${theCap(lockable.displayName)} must be closed before locking."))
            lockable.keyItemId == null ->
                outbound.send(OutboundEvent.SendError(sessionId, "That doesn't need a key."))
            else -> applyKeyAction(sessionId, me, lockable, LockableState.LOCKED, "lock", "locks")
        }
    }

    private suspend fun handleSearchContainer(
        sessionId: SessionId,
        keyword: String,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { _, room ->
        val feature = requireOpenContainer(sessionId, room, keyword, worldState, outbound) ?: return
        val contents = worldState?.getContainerContents(feature.id) ?: emptyList()
        if (contents.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "${theCap(feature.displayName)} is empty."))
        } else {
            val list = contents.map { it.item.displayName }.sorted().joinToString(", ")
            outbound.send(OutboundEvent.SendInfo(sessionId, "In ${the(feature.displayName)}: $list"))
        }
        emitContainerContents(sessionId, feature)
    }

    private suspend fun handleGetFrom(
        sessionId: SessionId,
        itemKeyword: String,
        containerKeyword: String,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { me, room ->
        val feature = requireOpenContainer(sessionId, room, containerKeyword, worldState, outbound) ?: return
        val item = worldState?.removeFromContainer(feature.id, itemKeyword)
        if (item == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "There is no '$itemKeyword' in ${the(feature.displayName)}."))
        } else {
            items.addToInventory(sessionId, item)
            outbound.send(OutboundEvent.SendInfo(sessionId, "You take ${item.item.displayName} from ${the(feature.displayName)}."))
            broadcastToRoomExcept(
                me.roomId,
                sessionId,
                "${me.name} takes ${item.item.displayName} from ${the(feature.displayName)}.",
                players,
                outbound,
            )
            emitContainerContents(sessionId, feature)
            syncItemsGmcp(sessionId, items, gmcpEmitter)
        }
    }

    private suspend fun handlePutIn(
        sessionId: SessionId,
        itemKeyword: String,
        containerKeyword: String,
    ): Unit = withPlayerAndRoom(sessionId, players, world) { me, room ->
        val feature = requireOpenContainer(sessionId, room, containerKeyword, worldState, outbound) ?: return
        val carried = items.peekInventoryItem(sessionId, itemKeyword)
        if (carried != null && carried.item.questItem) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "You can't stash ${carried.item.displayName} — it's a quest item.",
                ),
            )
            return
        }
        val item = items.removeFromInventory(sessionId, itemKeyword)
        if (item == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "You don't have any '$itemKeyword'."))
        } else {
            worldState?.addToContainer(feature.id, item)
            outbound.send(OutboundEvent.SendInfo(sessionId, "You put ${item.item.displayName} in ${the(feature.displayName)}."))
            broadcastToRoomExcept(
                me.roomId,
                sessionId,
                "${me.name} puts ${item.item.displayName} in ${the(feature.displayName)}.",
                players,
                outbound,
            )
            emitContainerContents(sessionId, feature)
            syncItemsGmcp(sessionId, items, gmcpEmitter)
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
        val pullMessage = "You pull ${the(feature.displayName)}. It moves ${newState.name.lowercase()}."
        outbound.send(OutboundEvent.SendInfo(sessionId, pullMessage))
        // Web clients drop plain text — surface the result via UI.Feedback so the
        // pull isn't just a silent state flip on the canvas (telnet still gets the
        // SendInfo above).
        gmcpEmitter?.sendUiFeedback(sessionId, "success", pullMessage, scope = "features", command = "pull")
        broadcastToRoomExcept(me.roomId, sessionId, "${me.name} pulls ${the(feature.displayName)}.", players, outbound)
        emitRoomFeatures(room)
        // Notify puzzle system of the lever interaction
        puzzleHandler?.onFeatureInteraction(sessionId, feature.keyword, "pull")
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
        gmcpEmitter?.sendUiFeedback(sessionId, "info", feature.text, scope = "features", command = "read")
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
            outbound.send(OutboundEvent.SendError(sessionId, "You don't have the key for ${the(lockable.displayName)}."))
        } else {
            lockable.applyState(newState)
            if (lockable.keyConsumed) items.removeFromInventory(sessionId, key.item.keyword)
            val msg = "You $verb ${the(lockable.displayName)}."
            outbound.send(OutboundEvent.SendInfo(sessionId, msg))
            gmcpEmitter?.sendUiFeedback(sessionId, "success", msg, scope = "features", command = verb)
            broadcastToRoomExcept(
                me.roomId,
                sessionId,
                "${me.name} $verbThirdPerson ${the(lockable.displayName)}.",
                players,
                outbound,
            )
            world.rooms[me.roomId]?.let { emitRoomFeatures(it) }
        }
    }

    private suspend fun emitRoomFeatures(room: Room) {
        val emitter = gmcpEmitter ?: return
        val payloads = room.features.map { feature -> buildFeaturePayload(feature, worldState, items) }
        for (p in players.playersInRoom(room.id)) {
            emitter.sendRoomFeatures(p.sessionId, payloads)
        }
    }

    private suspend fun emitContainerContents(
        sessionId: SessionId,
        feature: RoomFeature.Container,
    ) {
        val emitter = gmcpEmitter ?: return
        val contents = worldState?.getContainerContents(feature.id) ?: emptyList()
        emitter.sendContainerContents(
            sessionId,
            feature.id,
            feature.displayName,
            feature.keyword,
            contents.map { GmcpEmitter.ContainerItemPayload(name = it.item.displayName, keyword = it.item.keyword) },
        )
    }
}
