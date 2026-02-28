package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.DoorState
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.EffectType
import dev.ambon.engine.status.StatusEffectSystem
import java.time.Clock

class NavigationHandler(
    ctx: EngineContext,
    private val statusEffects: StatusEffectSystem? = null,
    private val dialogueSystem: DialogueSystem? = null,
    private val onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
    private val clock: Clock = Clock.systemUTC(),
) : CommandHandler {
    companion object {
        /** Cooldown between recall uses (5 minutes). */
        const val RECALL_COOLDOWN_MS = 300_000L
    }

    private val world = ctx.world
    private val players = ctx.players
    private val mobs = ctx.mobs
    private val items = ctx.items
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
        router.on<Command.Recall> { sid, _ -> handleRecall(sid) }
    }

    private suspend fun handleLook(sessionId: SessionId) {
        sendLook(sessionId, world, players, mobs, items, worldState, outbound, gmcpEmitter)
    }

    private suspend fun handleMove(
        sessionId: SessionId,
        cmd: Command.Move,
    ) {
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendText(sessionId, "You are in combat. Try 'flee'."))
            return
        }
        if (statusEffects?.hasPlayerEffect(sessionId, EffectType.ROOT) == true) {
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
                    if (doorState != DoorState.OPEN) {
                        val reason = if (doorState == DoorState.LOCKED) "locked" else "closed"
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

            val oldMembers = players.playersInRoom(from).filter { it.sessionId != me.sessionId }
            for (other in oldMembers) {
                outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} leaves."))
                gmcpEmitter?.sendRoomRemovePlayer(other.sessionId, me.name)
            }
            dialogueSystem?.onPlayerMoved(sessionId)
            players.moveTo(sessionId, to)
            val newMembers = players.playersInRoom(to).filter { it.sessionId != me.sessionId }
            for (other in newMembers) {
                outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} enters."))
                gmcpEmitter?.sendRoomAddPlayer(other.sessionId, me)
            }
            sendLook(sessionId, world, players, mobs, items, worldState, outbound, gmcpEmitter)
        }
    }

    private suspend fun handleRecall(sessionId: SessionId) {
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendText(sessionId, "You are fighting for your life and cannot recall!"))
            return
        }
        val me = players.get(sessionId) ?: return
        val now = clock.millis()
        if (now < me.recallCooldownUntilMs) {
            val secondsLeft = (me.recallCooldownUntilMs - now + 999L) / 1000L
            outbound.send(OutboundEvent.SendText(sessionId, "You need to rest before recalling again. ($secondsLeft seconds remaining)"))
            return
        }
        val target = players.recallTarget(sessionId) ?: return
        me.recallCooldownUntilMs = now + RECALL_COOLDOWN_MS
        outbound.send(OutboundEvent.SendText(sessionId, "You close your eyes and whisper a prayer..."))
        if (!world.rooms.containsKey(target)) {
            if (onCrossZoneMove != null) {
                router.suppressAutoPrompt()
                onCrossZoneMove.invoke(sessionId, target)
                return
            }
            outbound.send(OutboundEvent.SendError(sessionId, "Your recall point is unreachable."))
            return
        }
        val from = me.roomId
        for (other in players.playersInRoom(from).filter { it.sessionId != me.sessionId }) {
            outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} vanishes in a flash of light."))
            gmcpEmitter?.sendRoomRemovePlayer(other.sessionId, me.name)
        }
        dialogueSystem?.onPlayerMoved(sessionId)
        players.moveTo(sessionId, target)
        outbound.send(OutboundEvent.SendText(sessionId, "You feel a familiar warmth and find yourself back at your recall point."))
        for (other in players.playersInRoom(target).filter { it.sessionId != me.sessionId }) {
            outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} appears in a flash of light."))
            gmcpEmitter?.sendRoomAddPlayer(other.sessionId, me)
        }
        sendLook(sessionId, world, players, mobs, items, worldState, outbound, gmcpEmitter)
    }

    private suspend fun handleExits(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val r = world.rooms[me.roomId] ?: return
            outbound.send(OutboundEvent.SendInfo(sessionId, exitsLine(r)))
        }
    }

    private suspend fun handleLookDir(
        sessionId: SessionId,
        cmd: Command.LookDir,
    ) {
        players.withPlayer(sessionId) { me ->
            val r = world.rooms[me.roomId] ?: return
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
