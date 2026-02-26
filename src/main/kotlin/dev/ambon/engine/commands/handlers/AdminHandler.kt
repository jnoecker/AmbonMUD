package dev.ambon.engine.commands.handlers

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.broadcastToRoom
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import dev.ambon.sharding.BroadcastType
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage

class AdminHandler(
    router: CommandRouter,
    private val world: World,
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val combat: CombatSystem,
    private val outbound: OutboundBus,
    private val onShutdown: suspend () -> Unit = {},
    private val onMobSmited: (MobId) -> Unit = {},
    private val onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
    private val dialogueSystem: DialogueSystem? = null,
    private val gmcpEmitter: GmcpEmitter? = null,
    private val statusEffects: StatusEffectSystem? = null,
    private val interEngineBus: InterEngineBus? = null,
    private val engineId: String = "",
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val worldState: WorldStateRegistry? = null,
) {
    private var adminSpawnSeq = 0

    init {
        router.on<Command.Goto> { sid, cmd -> handleGoto(sid, cmd) }
        router.on<Command.Transfer> { sid, cmd -> handleTransfer(sid, cmd) }
        router.on<Command.Spawn> { sid, cmd -> handleSpawn(sid, cmd) }
        router.on<Command.Shutdown> { sid, _ -> handleShutdown(sid) }
        router.on<Command.Smite> { sid, cmd -> handleSmite(sid, cmd) }
        router.on<Command.Kick> { sid, cmd -> handleKick(sid, cmd) }
        router.on<Command.Dispel> { sid, cmd -> handleDispel(sid, cmd) }
    }

    private suspend fun handleGoto(
        sessionId: SessionId,
        cmd: Command.Goto,
    ) {
        if (!requireStaff(sessionId, players, outbound)) return
        val me = players.get(sessionId) ?: return
        val targetRoomId = resolveGotoArg(cmd.arg, me.roomId.zone, world)
        if (targetRoomId == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "No such room: ${cmd.arg}"))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        if (!world.rooms.containsKey(targetRoomId)) {
            if (onCrossZoneMove != null) {
                onCrossZoneMove.invoke(sessionId, targetRoomId)
                return
            }
            outbound.send(OutboundEvent.SendError(sessionId, "No such room: ${cmd.arg}"))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        players.moveTo(sessionId, targetRoomId)
        sendLook(sessionId, world, players, mobs, items, worldState, outbound, gmcpEmitter)
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleTransfer(
        sessionId: SessionId,
        cmd: Command.Transfer,
    ) {
        if (!requireStaff(sessionId, players, outbound)) return
        val me = players.get(sessionId) ?: return
        val targetSid = players.findSessionByName(cmd.playerName)
        if (targetSid == null) {
            if (interEngineBus != null) {
                interEngineBus.broadcast(
                    InterEngineMessage.TransferRequest(
                        staffName = me.name,
                        targetPlayerName = cmd.playerName,
                        targetRoomId = cmd.arg,
                    ),
                )
                outbound.send(OutboundEvent.SendInfo(sessionId, "Transfer request sent to other engines."))
            } else {
                outbound.send(OutboundEvent.SendError(sessionId, "Player not found: ${cmd.playerName}"))
            }
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val targetPlayer = players.get(targetSid) ?: return
        val targetRoomId = resolveGotoArg(cmd.arg, targetPlayer.roomId.zone, world)
        if (targetRoomId == null || !world.rooms.containsKey(targetRoomId)) {
            outbound.send(OutboundEvent.SendError(sessionId, "No such room: ${cmd.arg}"))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        players.moveTo(targetSid, targetRoomId)
        outbound.send(OutboundEvent.SendText(targetSid, "You are transported by a divine hand."))
        sendLook(targetSid, world, players, mobs, items, worldState, outbound, gmcpEmitter)
        outbound.send(OutboundEvent.SendPrompt(targetSid))
        outbound.send(OutboundEvent.SendInfo(sessionId, "Transferred ${targetPlayer.name} to ${targetRoomId.value}."))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleSpawn(
        sessionId: SessionId,
        cmd: Command.Spawn,
    ) {
        if (!requireStaff(sessionId, players, outbound)) return
        val me = players.get(sessionId) ?: return
        val template = findMobTemplate(cmd.templateArg)
        if (template == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "No mob template found: ${cmd.templateArg}"))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val seq = ++adminSpawnSeq
        val zone = template.id.value.substringBefore(':', template.id.value)
        val local = template.id.value.substringAfter(':', template.id.value)
        val newMobId = MobId("$zone:${local}_adm_$seq")
        mobs.upsert(
            MobState(
                id = newMobId,
                name = template.name,
                roomId = me.roomId,
                hp = template.maxHp,
                maxHp = template.maxHp,
                minDamage = template.minDamage,
                maxDamage = template.maxDamage,
                armor = template.armor,
                xpReward = template.xpReward,
                drops = template.drops,
            ),
        )
        outbound.send(OutboundEvent.SendInfo(sessionId, "${template.name} appears."))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleShutdown(sessionId: SessionId) {
        if (!requireStaff(sessionId, players, outbound)) return
        val me = players.get(sessionId) ?: return
        for (p in players.allPlayers()) {
            outbound.send(
                OutboundEvent.SendText(p.sessionId, "[SYSTEM] ${me.name} has initiated a server shutdown. Goodbye!"),
            )
        }
        interEngineBus?.broadcast(
            InterEngineMessage.GlobalBroadcast(
                broadcastType = BroadcastType.SHUTDOWN,
                senderName = me.name,
                text = "${me.name} has initiated a server shutdown. Goodbye!",
                sourceEngineId = engineId,
            ),
        )
        onShutdown()
    }

    private suspend fun handleSmite(
        sessionId: SessionId,
        cmd: Command.Smite,
    ) {
        if (!requireStaff(sessionId, players, outbound)) return
        val me = players.get(sessionId) ?: return

        val targetSid = players.findSessionByName(cmd.target)
        if (targetSid != null && targetSid != sessionId) {
            val targetPlayer = players.get(targetSid) ?: return
            combat.endCombatFor(targetSid)
            targetPlayer.hp = 1
            players.moveTo(targetSid, world.startRoom)
            outbound.send(
                OutboundEvent.SendText(targetSid, "A divine hand strikes you down. You awaken at the start, bruised and humbled."),
            )
            sendLook(targetSid, world, players, mobs, items, worldState, outbound, gmcpEmitter)
            outbound.send(OutboundEvent.SendPrompt(targetSid))
            outbound.send(OutboundEvent.SendInfo(sessionId, "Smote ${targetPlayer.name}."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }

        val targetMob = combat.findMobInRoom(me.roomId, cmd.target)
        if (targetMob == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "No player or mob named '${cmd.target}'."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        combat.onMobRemovedExternally(targetMob.id)
        dialogueSystem?.onMobRemoved(targetMob.id)
        items.removeMobItems(targetMob.id)
        mobs.remove(targetMob.id)
        onMobSmited(targetMob.id)
        broadcastToRoom(players, outbound, me.roomId, "${targetMob.name} is struck down by divine wrath.")
        for (p in players.playersInRoom(me.roomId)) {
            gmcpEmitter?.sendRoomRemoveMob(p.sessionId, targetMob.id.value)
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleKick(
        sessionId: SessionId,
        cmd: Command.Kick,
    ) {
        if (!requireStaff(sessionId, players, outbound)) return
        val targetSid = players.findSessionByName(cmd.playerName)
        if (targetSid == null) {
            if (interEngineBus != null) {
                interEngineBus.broadcast(InterEngineMessage.KickRequest(targetPlayerName = cmd.playerName))
                outbound.send(OutboundEvent.SendInfo(sessionId, "Kick request sent to other engines."))
            } else {
                outbound.send(OutboundEvent.SendError(sessionId, "Player not found: ${cmd.playerName}"))
            }
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        if (targetSid == sessionId) {
            outbound.send(OutboundEvent.SendError(sessionId, "You cannot kick yourself."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        outbound.send(OutboundEvent.Close(targetSid, "Kicked by staff."))
        outbound.send(OutboundEvent.SendInfo(sessionId, "${cmd.playerName} has been kicked."))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleDispel(
        sessionId: SessionId,
        cmd: Command.Dispel,
    ) {
        if (!requireStaff(sessionId, players, outbound)) return
        if (statusEffects == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Status effects are not available."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val targetSid = players.findSessionByName(cmd.target)
        if (targetSid != null) {
            statusEffects.removeAllFromPlayer(targetSid)
            val targetName = players.get(targetSid)?.name ?: cmd.target
            outbound.send(OutboundEvent.SendInfo(sessionId, "Dispelled all effects from $targetName."))
            outbound.send(OutboundEvent.SendText(targetSid, "All your effects have been dispelled."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val me = players.get(sessionId) ?: return
        val mob = combat.findMobInRoom(me.roomId, cmd.target)
        if (mob != null) {
            statusEffects.removeAllFromMob(mob.id)
            outbound.send(OutboundEvent.SendInfo(sessionId, "Dispelled all effects from ${mob.name}."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        outbound.send(OutboundEvent.SendError(sessionId, "No player or mob named '${cmd.target}'."))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private fun findMobTemplate(arg: String): dev.ambon.domain.world.MobSpawn? {
        val trimmed = arg.trim()
        return if (':' in trimmed) {
            world.mobSpawns.firstOrNull { it.id.value.equals(trimmed, ignoreCase = true) }
        } else {
            val lowerLocal = trimmed.lowercase()
            world.mobSpawns.firstOrNull {
                it.id.value.substringAfter(':', it.id.value).lowercase() == lowerLocal
            }
        }
    }
}
