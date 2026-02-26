package dev.ambon.engine.commands.handlers

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.sharding.BroadcastType
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage
import dev.ambon.sharding.PlayerLocationIndex

class CommunicationHandler(
    router: CommandRouter,
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val gmcpEmitter: GmcpEmitter? = null,
    private val groupSystem: GroupSystem? = null,
    private val interEngineBus: InterEngineBus? = null,
    private val playerLocationIndex: PlayerLocationIndex? = null,
    private val engineId: String = "",
    private val onRemoteWho: (suspend (SessionId) -> Unit)? = null,
) {
    init {
        router.on<Command.Say> { sid, cmd -> handleSay(sid, cmd) }
        router.on<Command.Emote> { sid, cmd -> handleEmote(sid, cmd) }
        router.on<Command.Tell> { sid, cmd -> handleTell(sid, cmd) }
        router.on<Command.Whisper> { sid, cmd -> handleWhisper(sid, cmd) }
        router.on<Command.Gossip> { sid, cmd -> handleGossip(sid, cmd) }
        router.on<Command.Shout> { sid, cmd -> handleShout(sid, cmd) }
        router.on<Command.Ooc> { sid, cmd -> handleOoc(sid, cmd) }
        router.on<Command.Pose> { sid, cmd -> handlePose(sid, cmd) }
        router.on<Command.Who> { sid, _ -> handleWho(sid) }
    }

    private suspend fun handleSay(
        sessionId: SessionId,
        cmd: Command.Say,
    ) {
        val me = players.get(sessionId) ?: return
        val roomId = me.roomId
        val members = players.playersInRoom(roomId)

        outbound.send(OutboundEvent.SendText(sessionId, "You say: ${cmd.message}"))
        for (other in members) {
            if (other == me) continue
            outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} says: ${cmd.message}"))
        }
        for (member in members) {
            gmcpEmitter?.sendCommChannel(member.sessionId, "say", me.name, cmd.message)
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleEmote(
        sessionId: SessionId,
        cmd: Command.Emote,
    ) {
        val me = players.get(sessionId) ?: return
        val roomId = me.roomId
        val members = players.playersInRoom(roomId)
        for (other in members) {
            outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} ${cmd.message}"))
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleTell(
        sessionId: SessionId,
        cmd: Command.Tell,
    ) {
        val me = players.get(sessionId) ?: return
        val targetSid = players.findSessionByName(cmd.target)
        if (targetSid == null) {
            if (interEngineBus != null) {
                val tell =
                    InterEngineMessage.TellMessage(
                        fromName = me.name,
                        toName = cmd.target,
                        text = cmd.message,
                    )
                val targetEngineId = playerLocationIndex?.lookupEngineId(cmd.target)
                if (targetEngineId != null && targetEngineId != engineId) {
                    interEngineBus.sendTo(targetEngineId, tell)
                } else {
                    interEngineBus.broadcast(tell)
                }
                outbound.send(OutboundEvent.SendText(sessionId, "You tell ${cmd.target}: ${cmd.message}"))
            } else {
                outbound.send(OutboundEvent.SendError(sessionId, "No such player: ${cmd.target}"))
            }
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        if (targetSid == sessionId) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You tell yourself: ${cmd.message}"))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val target = players.get(targetSid) ?: return
        outbound.send(OutboundEvent.SendText(sessionId, "You tell ${cmd.target}: ${cmd.message}"))
        outbound.send(OutboundEvent.SendText(targetSid, "${me.name} tells you: ${cmd.message}"))
        gmcpEmitter?.sendCommChannel(sessionId, "tell", me.name, cmd.message)
        gmcpEmitter?.sendCommChannel(targetSid, "tell", me.name, cmd.message)
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleWhisper(
        sessionId: SessionId,
        cmd: Command.Whisper,
    ) {
        val me = players.get(sessionId) ?: return
        val targetSid = players.findSessionByName(cmd.target)
        if (targetSid == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "No such player: ${cmd.target}"))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        if (targetSid == sessionId) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You whisper to yourself: ${cmd.message}"))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val target = players.get(targetSid) ?: return
        if (target.roomId != me.roomId) {
            outbound.send(OutboundEvent.SendError(sessionId, "${target.name} is not here."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        outbound.send(OutboundEvent.SendText(sessionId, "You whisper to ${target.name}: ${cmd.message}"))
        outbound.send(OutboundEvent.SendText(targetSid, "${me.name} whispers to you: ${cmd.message}"))
        gmcpEmitter?.sendCommChannel(sessionId, "whisper", me.name, cmd.message)
        gmcpEmitter?.sendCommChannel(targetSid, "whisper", me.name, cmd.message)
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleGossip(
        sessionId: SessionId,
        cmd: Command.Gossip,
    ) {
        val me = players.get(sessionId) ?: return
        for (p in players.allPlayers()) {
            if (p.sessionId == sessionId) {
                outbound.send(OutboundEvent.SendText(sessionId, "You gossip: ${cmd.message}"))
            } else {
                outbound.send(OutboundEvent.SendText(p.sessionId, "[GOSSIP] ${me.name}: ${cmd.message}"))
            }
            gmcpEmitter?.sendCommChannel(p.sessionId, "gossip", me.name, cmd.message)
        }
        interEngineBus?.broadcast(
            InterEngineMessage.GlobalBroadcast(
                broadcastType = BroadcastType.GOSSIP,
                senderName = me.name,
                text = cmd.message,
                sourceEngineId = engineId,
            ),
        )
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleShout(
        sessionId: SessionId,
        cmd: Command.Shout,
    ) {
        val me = players.get(sessionId) ?: return
        val zone = me.roomId.zone
        outbound.send(OutboundEvent.SendText(sessionId, "You shout: ${cmd.message}"))
        for (p in players.playersInZone(zone)) {
            if (p.sessionId != sessionId) {
                outbound.send(OutboundEvent.SendText(p.sessionId, "[SHOUT] ${me.name}: ${cmd.message}"))
            }
            gmcpEmitter?.sendCommChannel(p.sessionId, "shout", me.name, cmd.message)
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleOoc(
        sessionId: SessionId,
        cmd: Command.Ooc,
    ) {
        val me = players.get(sessionId) ?: return
        for (p in players.allPlayers()) {
            if (p.sessionId == sessionId) {
                outbound.send(OutboundEvent.SendText(sessionId, "You say OOC: ${cmd.message}"))
            } else {
                outbound.send(OutboundEvent.SendText(p.sessionId, "[OOC] ${me.name}: ${cmd.message}"))
            }
            gmcpEmitter?.sendCommChannel(p.sessionId, "ooc", me.name, cmd.message)
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handlePose(
        sessionId: SessionId,
        cmd: Command.Pose,
    ) {
        val me = players.get(sessionId) ?: return
        if (!cmd.message.contains(me.name, ignoreCase = true)) {
            outbound.send(OutboundEvent.SendError(sessionId, "Your pose must include your name (${me.name})."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val roomId = me.roomId
        val members = players.playersInRoom(roomId)
        for (other in members) {
            outbound.send(OutboundEvent.SendText(other.sessionId, cmd.message))
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleWho(sessionId: SessionId) {
        val list =
            players
                .allPlayers()
                .sortedBy { it.name }
                .joinToString(separator = ", ") { p ->
                    val t = p.activeTitle
                    val grouped = groupSystem?.isGrouped(p.sessionId) == true
                    val prefix =
                        if (t != null && grouped) {
                            "[$t] [G] "
                        } else if (t != null) {
                            "[$t] "
                        } else if (grouped) {
                            "[G] "
                        } else {
                            ""
                        }
                    "$prefix${p.name}"
                }

        outbound.send(OutboundEvent.SendInfo(sessionId, "Online: $list"))
        if (onRemoteWho != null) {
            onRemoteWho.invoke(sessionId)
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }
}
