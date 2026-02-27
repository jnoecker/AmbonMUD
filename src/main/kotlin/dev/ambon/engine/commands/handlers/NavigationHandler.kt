package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.DoorState
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.EffectType
import dev.ambon.engine.status.StatusEffectSystem

class NavigationHandler(
    private val router: CommandRouter,
    ctx: EngineContext,
    private val statusEffects: StatusEffectSystem? = null,
    private val dialogueSystem: DialogueSystem? = null,
    private val onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
) {
    private val world = ctx.world
    private val players = ctx.players
    private val mobs = ctx.mobs
    private val items = ctx.items
    private val combat = ctx.combat
    private val outbound = ctx.outbound
    private val worldState = ctx.worldState
    private val gmcpEmitter = ctx.gmcpEmitter

    init {
        router.on<Command.Look> { sid, _ -> handleLook(sid) }
        router.on<Command.Move> { sid, cmd -> handleMove(sid, cmd) }
        router.on<Command.Exits> { sid, _ -> handleExits(sid) }
        router.on<Command.LookDir> { sid, cmd -> handleLookDir(sid, cmd) }
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
