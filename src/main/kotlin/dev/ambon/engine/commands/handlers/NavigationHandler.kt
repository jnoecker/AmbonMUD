package dev.ambon.engine.commands.handlers

import dev.ambon.config.RecallConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.LockableState
import dev.ambon.engine.HouseEntryResult
import dev.ambon.engine.HousingSystem
import dev.ambon.engine.ceilSeconds
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.matchesKeyword
import dev.ambon.engine.status.StatusEffectSystem
import java.time.Clock

class NavigationHandler(
    ctx: EngineContext,
    private val statusEffects: StatusEffectSystem? = null,
    private val dialogueSystem: DialogueSystem? = null,
    private val onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val recallConfig: RecallConfig = RecallConfig(),
    private val housingSystem: HousingSystem? = null,
    private val onPlayerMoved: (suspend (SessionId, RoomId) -> Unit)? = null,
) : CommandHandler {
    private val ctx = ctx
    private val world = ctx.world
    private val players = ctx.players
    private val combat = ctx.combat
    private val outbound = ctx.outbound
    private val worldState = ctx.worldState
    private val gmcpEmitter = ctx.gmcpEmitter
    private lateinit var router: CommandRouter

    /** Easter-egg petition keywords → zone:startRoom targets. */
    private val petitionTargets: Map<String, RoomId> = mapOf(
        "pbrae" to RoomId("pbrae:gravel_road"),
        "peanut" to RoomId("pbrae:gravel_road"),
        "braelynn" to RoomId("pbrae:gravel_road"),
        "wesley" to RoomId("wesleyalis:gravel_road_2"),
        "aurora" to RoomId("wesleyalis:gravel_road_2"),
        "wesleyalis" to RoomId("wesleyalis:gravel_road_2"),
        "trevor" to RoomId("trailey:cul_de_sac"),
        "hailey" to RoomId("trailey:cul_de_sac"),
        "trailey" to RoomId("trailey:cul_de_sac"),
        "noecker" to RoomId("noecker_resume:lobby"),
    )

    override fun register(router: CommandRouter) {
        this.router = router
        router.on<Command.Look> { sid, _ -> handleLook(sid) }
        router.on<Command.Move> { sid, cmd -> handleMove(sid, cmd) }
        router.on<Command.Exits> { sid, _ -> handleExits(sid) }
        router.on<Command.LookDir> { sid, cmd -> handleLookDir(sid, cmd) }
        router.on<Command.LookAt> { sid, cmd -> handleLookAt(sid, cmd) }
        router.on<Command.Recall> { sid, _ -> handleRecall(sid) }
        router.on<Command.Petition> { sid, cmd -> handlePetition(sid, cmd) }
    }

    private suspend fun handleLook(sessionId: SessionId) {
        ctx.sendLook(sessionId)
    }

    private suspend fun handleMove(
        sessionId: SessionId,
        cmd: Command.Move,
    ) {
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, "You are in combat. Try 'flee'."))
            return
        }
        if (statusEffects?.hasPlayerEffect(sessionId, "root") == true) {
            outbound.send(OutboundEvent.SendError(sessionId, "You are rooted and cannot move!"))
            return
        }
        players.withPlayer(sessionId) { me ->
            val from = me.roomId
            val room = world.rooms[from] ?: return
            val to = room.exits[cmd.dir]

            if (to == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You can't go that way."))
                return
            }

            if (worldState != null) {
                val door = worldState.doorOnExit(from, cmd.dir)
                if (door != null) {
                    val doorState = worldState.getDoorState(door.id)
                    if (doorState != LockableState.OPEN) {
                        val reason = if (doorState == LockableState.LOCKED) "locked" else "closed"
                        outbound.send(OutboundEvent.SendText(sessionId, "The ${door.displayName} is $reason."))
                        return
                    }
                }
            }

            // Housing exit: resolve dynamic destination
            if (housingSystem != null && housingSystem.isHouseExit(to)) {
                val origin = housingSystem.resolveHouseExit(sessionId)
                if (origin != null) {
                    // Origin may be on a different engine in sharded deployments
                    if (!world.rooms.containsKey(origin)) {
                        if (!attemptCrossZoneMove(sessionId, origin, onCrossZoneMove, router::suppressAutoPrompt)) {
                            outbound.send(OutboundEvent.SendText(sessionId, "The exit shimmers but does not yield."))
                        }
                        return
                    }
                    movePlayerWithNotify(
                        sessionId,
                        from,
                        origin,
                        "leaves.",
                        "enters.",
                        players,
                        outbound,
                        gmcpEmitter,
                        dialogueSystem,
                    )
                    onPlayerMoved?.invoke(sessionId, origin)
                    outbound.send(OutboundEvent.SendText(sessionId, "You step outside and find yourself back where you came from."))
                    ctx.sendLook(sessionId)
                } else {
                    outbound.send(OutboundEvent.SendText(sessionId, "The exit shimmers but does not yield."))
                }
                return
            }

            if (room.remoteExits.contains(cmd.dir) || !world.rooms.containsKey(to)) {
                if (!attemptCrossZoneMove(sessionId, to, onCrossZoneMove, router::suppressAutoPrompt)) {
                    outbound.send(OutboundEvent.SendText(sessionId, "The way shimmers but does not yield."))
                }
                return
            }

            movePlayerWithNotify(
                sessionId,
                from,
                to,
                "leaves.",
                "enters.",
                players,
                outbound,
                gmcpEmitter,
                dialogueSystem,
            )
            onPlayerMoved?.invoke(sessionId, to)
            ctx.sendLook(sessionId)
        }
    }

    private suspend fun handleRecall(sessionId: SessionId) {
        val msgs = recallConfig.messages
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, msgs.combatBlocked))
            return
        }
        val me = players.get(sessionId) ?: return

        // If the player has a house, recall goes to the house
        if (housingSystem != null && me.hasHouse) {
            // Already in own house? No-op.
            if (housingSystem.isInOwnHouse(sessionId)) {
                outbound.send(OutboundEvent.SendText(sessionId, "You're already home."))
                return
            }

            val now = clock.millis()
            if (now < me.recallCooldownUntilMs) {
                val secondsLeft = (me.recallCooldownUntilMs - now).ceilSeconds()
                outbound.send(OutboundEvent.SendText(sessionId, msgs.cooldownRemaining.replace("{seconds}", secondsLeft.toString())))
                return
            }
            me.recallCooldownUntilMs = now + recallConfig.cooldownMs

            // Origin for the house exit is the player's recall inn (or start room)
            val recallInn = players.recallTarget(sessionId) ?: world.startRoom
            when (val result = housingSystem.enterOwnHouse(sessionId, recallInn)) {
                is HouseEntryResult.Success -> {
                    outbound.send(OutboundEvent.SendText(sessionId, msgs.castBegin))
                    val from = me.roomId
                    movePlayerWithNotify(
                        sessionId,
                        from,
                        result.entryRoomId,
                        msgs.departNotice,
                        msgs.arriveNotice,
                        players,
                        outbound,
                        gmcpEmitter,
                        dialogueSystem,
                    )
                    onPlayerMoved?.invoke(sessionId, result.entryRoomId)
                    outbound.send(OutboundEvent.SendText(sessionId, "You feel a familiar warmth and find yourself home."))
                    ctx.sendLook(sessionId)
                }
                is HouseEntryResult.Error -> {
                    outbound.send(OutboundEvent.SendError(sessionId, result.message))
                }
            }
            return
        }

        // Standard recall (no house)
        val now = clock.millis()
        if (now < me.recallCooldownUntilMs) {
            val secondsLeft = (me.recallCooldownUntilMs - now).ceilSeconds()
            outbound.send(OutboundEvent.SendText(sessionId, msgs.cooldownRemaining.replace("{seconds}", secondsLeft.toString())))
            return
        }
        val target = players.recallTarget(sessionId) ?: return
        me.recallCooldownUntilMs = now + recallConfig.cooldownMs
        outbound.send(OutboundEvent.SendText(sessionId, msgs.castBegin))
        if (!world.rooms.containsKey(target)) {
            if (!attemptCrossZoneMove(sessionId, target, onCrossZoneMove, router::suppressAutoPrompt)) {
                outbound.send(OutboundEvent.SendError(sessionId, msgs.unreachable))
            }
            return
        }
        val from = me.roomId
        movePlayerWithNotify(
            sessionId,
            from,
            target,
            msgs.departNotice,
            msgs.arriveNotice,
            players,
            outbound,
            gmcpEmitter,
            dialogueSystem,
        )
        onPlayerMoved?.invoke(sessionId, target)
        outbound.send(OutboundEvent.SendText(sessionId, msgs.arrival))
        ctx.sendLook(sessionId)
    }

    private suspend fun handlePetition(sessionId: SessionId, cmd: Command.Petition) {
        val target = petitionTargets[cmd.keyword]
        if (target == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Your petition goes unanswered."))
            return
        }
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, "You can't petition while in combat!"))
            return
        }
        val me = players.get(sessionId) ?: return
        if (!world.rooms.containsKey(target)) {
            outbound.send(OutboundEvent.SendError(sessionId, "Your petition goes unanswered."))
            return
        }
        val from = me.roomId
        outbound.send(OutboundEvent.SendText(sessionId, "You whisper a name into the void... and the world shifts around you."))
        movePlayerWithNotify(
            sessionId,
            from,
            target,
            "vanishes in a shimmer of light",
            "appears in a shimmer of light",
            players,
            outbound,
            gmcpEmitter,
            dialogueSystem,
        )
        onPlayerMoved?.invoke(sessionId, target)
        ctx.sendLook(sessionId)
    }

    private suspend fun handleExits(sessionId: SessionId) {
        withPlayerAndRoom(sessionId, players, world) { _, r ->
            outbound.send(OutboundEvent.SendInfo(sessionId, exitsLine(r)))
        }
    }

    private suspend fun handleLookAt(
        sessionId: SessionId,
        cmd: Command.LookAt,
    ) {
        val me = players.get(sessionId) ?: return
        val roomId = me.roomId

        // Try mob first
        val mob = ctx.mobs.findInRoomByKeyword(roomId, cmd.target).firstOrNull()
        if (mob != null) {
            val desc = mob.description.ifEmpty { "You see nothing special about ${mob.name}." }
            outbound.send(OutboundEvent.SendText(sessionId, "${mob.name}: $desc"))
            gmcpEmitter?.sendLookTarget(sessionId, "mob", mob.name, desc)
            return
        }

        // Try room items
        val roomItems = ctx.items.itemsInRoom(roomId)
        val roomItem = roomItems.firstOrNull { it.matchesKeyword(cmd.target) }
        if (roomItem != null) {
            val desc = roomItem.item.description.ifEmpty { "You see nothing special about ${roomItem.item.displayName}." }
            outbound.send(OutboundEvent.SendText(sessionId, "${roomItem.item.displayName}: $desc"))
            gmcpEmitter?.sendLookTarget(sessionId, "item", roomItem.item.displayName, desc, image = roomItem.item.image)
            return
        }

        // Try inventory items
        val invItems = ctx.items.inventory(sessionId)
        val invItem = invItems.firstOrNull { it.matchesKeyword(cmd.target) }
        if (invItem != null) {
            val desc = invItem.item.description.ifEmpty { "You see nothing special about ${invItem.item.displayName}." }
            outbound.send(OutboundEvent.SendText(sessionId, "${invItem.item.displayName}: $desc"))
            gmcpEmitter?.sendLookTarget(sessionId, "item", invItem.item.displayName, desc, image = invItem.item.image)
            return
        }

        // Try equipment
        val equipment = ctx.items.equipment(sessionId)
        val eqItem = equipment.values.firstOrNull { it.matchesKeyword(cmd.target) }
        if (eqItem != null) {
            val desc = eqItem.item.description.ifEmpty { "You see nothing special about ${eqItem.item.displayName}." }
            outbound.send(OutboundEvent.SendText(sessionId, "${eqItem.item.displayName}: $desc"))
            gmcpEmitter?.sendLookTarget(sessionId, "item", eqItem.item.displayName, desc, image = eqItem.item.image)
            return
        }

        // Try other players in the room
        val otherPlayer = players.playersInRoom(roomId)
            .firstOrNull { it.sessionId != sessionId && it.name.contains(cmd.target, ignoreCase = true) }
        if (otherPlayer != null) {
            val p = otherPlayer
            val playerDesc = "You see ${p.name}, a level ${p.level} ${p.race} ${p.playerClass}."
            outbound.send(OutboundEvent.SendText(sessionId, playerDesc))
            gmcpEmitter?.sendLookTarget(
                sessionId,
                "player",
                p.name,
                playerDesc,
                level = p.level,
                race = p.race,
                playerClass = p.playerClass,
            )
            return
        }

        val msg = "You don't see '${cmd.target}' here."
        outbound.send(OutboundEvent.SendError(sessionId, msg))
        gmcpEmitter?.sendUiFeedback(sessionId, "error", msg, code = "TARGET_NOT_FOUND", scope = "navigation", command = "look")
    }

    private suspend fun handleLookDir(
        sessionId: SessionId,
        cmd: Command.LookDir,
    ) {
        withPlayerAndRoom(sessionId, players, world) { _, r ->
            val targetId = r.exits[cmd.dir]
            if (targetId == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You see nothing that way."))
            } else {
                val target = world.rooms[targetId]
                if (target == null || r.remoteExits.contains(cmd.dir)) {
                    outbound.send(OutboundEvent.SendText(sessionId, "You see a shimmering passage."))
                } else {
                    outbound.send(OutboundEvent.SendText(sessionId, target.title))
                }
            }
        }
    }
}
