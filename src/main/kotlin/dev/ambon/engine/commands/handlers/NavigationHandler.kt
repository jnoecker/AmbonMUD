package dev.ambon.engine.commands.handlers

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.DoorState
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.EffectType
import dev.ambon.engine.status.StatusEffectSystem

class NavigationHandler(
    router: CommandRouter,
    private val world: World,
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val combat: CombatSystem,
    private val outbound: OutboundBus,
    private val worldState: WorldStateRegistry? = null,
    private val gmcpEmitter: GmcpEmitter? = null,
    private val statusEffects: StatusEffectSystem? = null,
    private val dialogueSystem: DialogueSystem? = null,
    private val onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
) {
    init {
        router.on<Command.Look> { sid, _ -> handleLook(sid) }
        router.on<Command.Move> { sid, cmd -> handleMove(sid, cmd) }
        router.on<Command.Exits> { sid, _ -> handleExits(sid) }
        router.on<Command.LookDir> { sid, cmd -> handleLookDir(sid, cmd) }
    }

    private suspend fun handleLook(sessionId: SessionId) {
        sendLook(sessionId, world, players, mobs, items, worldState, outbound, gmcpEmitter)
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleMove(
        sessionId: SessionId,
        cmd: Command.Move,
    ) {
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendText(sessionId, "You are in combat. Try 'flee'."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        if (statusEffects?.hasPlayerEffect(sessionId, EffectType.ROOT) == true) {
            outbound.send(OutboundEvent.SendText(sessionId, "You are rooted and cannot move!"))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val me = players.get(sessionId) ?: return
        val from = me.roomId
        val room = world.rooms[from] ?: return
        val to = room.exits[cmd.dir]

        if (to == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "You can't go that way."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }

        if (worldState != null) {
            val door = worldState.doorOnExit(from, cmd.dir)
            if (door != null) {
                val doorState = worldState.getDoorState(door.id)
                if (doorState != DoorState.OPEN) {
                    val reason = if (doorState == DoorState.LOCKED) "locked" else "closed"
                    outbound.send(OutboundEvent.SendText(sessionId, "The ${door.displayName} is $reason."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
            }
        }

        if (room.remoteExits.contains(cmd.dir) || !world.rooms.containsKey(to)) {
            if (onCrossZoneMove != null) {
                onCrossZoneMove.invoke(sessionId, to)
                return
            } else {
                outbound.send(OutboundEvent.SendText(sessionId, "The way shimmers but does not yield."))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
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
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleExits(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        val r = world.rooms[me.roomId] ?: return
        outbound.send(OutboundEvent.SendInfo(sessionId, exitsLine(r)))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleLookDir(
        sessionId: SessionId,
        cmd: Command.LookDir,
    ) {
        val me = players.get(sessionId) ?: return
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
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }
}
