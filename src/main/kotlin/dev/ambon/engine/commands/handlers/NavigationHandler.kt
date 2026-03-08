package dev.ambon.engine.commands.handlers

import dev.ambon.config.RecallConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.LockableState
import dev.ambon.engine.ceilSeconds
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectSystem
import java.time.Clock

class NavigationHandler(
    ctx: EngineContext,
    private val statusEffects: StatusEffectSystem? = null,
    private val dialogueSystem: DialogueSystem? = null,
    private val onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val recallConfig: RecallConfig = RecallConfig(),
) : CommandHandler {
    private val ctx = ctx
    private val world = ctx.world
    private val players = ctx.players
    private val combat = ctx.combat
    private val outbound = ctx.outbound
    private val worldState = ctx.worldState
    private val gmcpEmitter = ctx.gmcpEmitter
    private lateinit var router: CommandRouter

    override fun register(router: CommandRouter) {
        this.router = router
        router.on<Command.Look> { sid, _ -> handleLook(sid) }
        router.on<Command.Move> { sid, cmd -> handleMove(sid, cmd) }
        router.on<Command.Exits> { sid, _ -> handleExits(sid) }
        router.on<Command.LookDir> { sid, cmd -> handleLookDir(sid, cmd) }
        router.on<Command.LookAt> { sid, cmd -> handleLookAt(sid, cmd) }
        router.on<Command.Recall> { sid, _ -> handleRecall(sid) }
    }

    private suspend fun handleLook(sessionId: SessionId) {
        ctx.sendLook(sessionId)
    }

    private suspend fun handleMove(
        sessionId: SessionId,
        cmd: Command.Move,
    ) {
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendText(sessionId, "You are in combat. Try 'flee'."))
            return
        }
        if (statusEffects?.hasPlayerEffect(sessionId, "root") == true) {
            outbound.send(OutboundEvent.SendText(sessionId, "You are rooted and cannot move!"))
            return
        }
        players.withPlayer(sessionId) { me ->
            val from = me.roomId
            val room = world.rooms[from] ?: return
            val to = room.exits[cmd.dir]

            if (to == null) {
                outbound.send(OutboundEvent.SendText(sessionId, "You can't go that way."))
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

            if (room.remoteExits.contains(cmd.dir) || !world.rooms.containsKey(to)) {
                if (onCrossZoneMove != null) {
                    router.suppressAutoPrompt()
                    onCrossZoneMove.invoke(sessionId, to)
                    return
                } else {
                    outbound.send(OutboundEvent.SendText(sessionId, "The way shimmers but does not yield."))
                    return
                }
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
            ctx.sendLook(sessionId)
        }
    }

    private suspend fun handleRecall(sessionId: SessionId) {
        val msgs = recallConfig.messages
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendText(sessionId, msgs.combatBlocked))
            return
        }
        val me = players.get(sessionId) ?: return
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
            if (onCrossZoneMove != null) {
                router.suppressAutoPrompt()
                onCrossZoneMove.invoke(sessionId, target)
                return
            }
            outbound.send(OutboundEvent.SendError(sessionId, msgs.unreachable))
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
        outbound.send(OutboundEvent.SendText(sessionId, msgs.arrival))
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
            return
        }

        // Try room items
        val roomItems = ctx.items.itemsInRoom(roomId)
        val roomItem = roomItems.firstOrNull { matchesItemKeyword(it, cmd.target) }
        if (roomItem != null) {
            val desc = roomItem.item.description.ifEmpty { "You see nothing special about ${roomItem.item.displayName}." }
            outbound.send(OutboundEvent.SendText(sessionId, "${roomItem.item.displayName}: $desc"))
            return
        }

        // Try inventory items
        val invItems = ctx.items.inventory(sessionId)
        val invItem = invItems.firstOrNull { matchesItemKeyword(it, cmd.target) }
        if (invItem != null) {
            val desc = invItem.item.description.ifEmpty { "You see nothing special about ${invItem.item.displayName}." }
            outbound.send(OutboundEvent.SendText(sessionId, "${invItem.item.displayName}: $desc"))
            return
        }

        // Try equipment
        val equipment = ctx.items.equipment(sessionId)
        val eqItem = equipment.values.firstOrNull { matchesItemKeyword(it, cmd.target) }
        if (eqItem != null) {
            val desc = eqItem.item.description.ifEmpty { "You see nothing special about ${eqItem.item.displayName}." }
            outbound.send(OutboundEvent.SendText(sessionId, "${eqItem.item.displayName}: $desc"))
            return
        }

        // Try other players in the room
        val otherPlayer = players.playersInRoom(roomId)
            .firstOrNull { it.sessionId != sessionId && it.name.contains(cmd.target, ignoreCase = true) }
        if (otherPlayer != null) {
            val p = otherPlayer
            val playerDesc = "You see ${p.name}, a level ${p.level} ${p.race} ${p.playerClass}."
            outbound.send(OutboundEvent.SendText(sessionId, playerDesc))
            return
        }

        outbound.send(OutboundEvent.SendError(sessionId, "You don't see '${cmd.target}' here."))
    }

    private fun matchesItemKeyword(item: dev.ambon.domain.items.ItemInstance, input: String): Boolean {
        val lower = input.lowercase()
        return item.item.keyword.equals(lower, ignoreCase = true) ||
            item.item.displayName.contains(lower, ignoreCase = true)
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
