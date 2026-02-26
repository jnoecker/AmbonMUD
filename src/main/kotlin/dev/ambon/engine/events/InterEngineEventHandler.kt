package dev.ambon.engine.events

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.RegenSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import dev.ambon.sharding.BroadcastType
import dev.ambon.sharding.HandoffAckResult
import dev.ambon.sharding.HandoffManager
import dev.ambon.sharding.HandoffResult
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage
import dev.ambon.sharding.PlayerLocationIndex
import dev.ambon.sharding.PlayerSummary
import io.github.oshai.kotlinlogging.KLogger

class InterEngineEventHandler(
    private val handoffManager: HandoffManager?,
    private val playerLocationIndex: PlayerLocationIndex?,
    private val players: PlayerRegistry,
    private val router: CommandRouter,
    private val outbound: OutboundBus,
    private val engineId: String,
    private val interEngineBus: InterEngineBus?,
    private val onShutdown: suspend () -> Unit,
    private val world: World,
    private val combatSystem: CombatSystem,
    private val regenSystem: RegenSystem,
    private val statusEffectSystem: StatusEffectSystem,
    private val resolveRoomId: (String, String) -> RoomId?,
    private val onWhoResponse: (InterEngineMessage.WhoResponse) -> Unit,
    private val logger: KLogger,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    suspend fun onInterEngineMessage(msg: InterEngineMessage) {
        metrics.onInterEngineHandlerEvent()
        when (msg) {
            is InterEngineMessage.PlayerHandoff -> {
                val mgr = handoffManager ?: return
                val sid = mgr.acceptHandoff(msg) ?: return
                playerLocationIndex?.register(msg.playerState.name)
                router.handle(sid, Command.Look)
            }

            is InterEngineMessage.HandoffAck -> {
                val mgr = handoffManager ?: return
                when (val result = mgr.handleAck(msg)) {
                    is HandoffAckResult.Completed -> {
                        playerLocationIndex?.unregister(result.playerName)
                        logger.debug {
                            "Handoff ack completed: session=${msg.sessionId} " +
                                "targetEngine=${result.targetEngine.engineId}"
                        }
                    }

                    is HandoffAckResult.Failed -> {
                        val sid = SessionId(msg.sessionId)
                        if (players.get(sid) != null) {
                            val reason =
                                result.errorMessage
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "Target engine rejected handoff."
                            outbound.send(OutboundEvent.SendError(sid, "Cross-zone move failed: $reason"))
                            outbound.send(OutboundEvent.SendPrompt(sid))
                        }
                        logger.warn { "Handoff failed: session=${msg.sessionId} error=${result.errorMessage}" }
                    }

                    HandoffAckResult.NotPending -> {
                        logger.debug { "Ignoring handoff ack for non-pending session=${msg.sessionId}" }
                    }
                }
            }

            is InterEngineMessage.GlobalBroadcast -> {
                if (msg.sourceEngineId == engineId) return
                when (msg.broadcastType) {
                    BroadcastType.GOSSIP -> {
                        for (p in players.allPlayers()) {
                            outbound.send(OutboundEvent.SendText(p.sessionId, "[GOSSIP] ${msg.senderName}: ${msg.text}"))
                        }
                    }

                    BroadcastType.SHUTDOWN -> {
                        for (p in players.allPlayers()) {
                            outbound.send(OutboundEvent.SendText(p.sessionId, "[SYSTEM] ${msg.text}"))
                        }
                        onShutdown()
                    }

                    BroadcastType.ANNOUNCEMENT -> {
                        for (p in players.allPlayers()) {
                            outbound.send(OutboundEvent.SendText(p.sessionId, "[ANNOUNCEMENT] ${msg.text}"))
                        }
                    }
                }
            }

            is InterEngineMessage.TellMessage -> {
                val targetSid = players.findSessionByName(msg.toName) ?: return
                outbound.send(OutboundEvent.SendText(targetSid, "${msg.fromName} tells you: ${msg.text}"))
            }

            is InterEngineMessage.WhoRequest -> {
                if (msg.replyToEngineId == engineId) return
                val localPlayers =
                    players.allPlayers().map {
                        PlayerSummary(name = it.name, roomId = it.roomId.value, level = it.level)
                    }
                interEngineBus?.sendTo(
                    msg.replyToEngineId,
                    InterEngineMessage.WhoResponse(
                        requestId = msg.requestId,
                        players = localPlayers,
                    ),
                )
            }

            is InterEngineMessage.WhoResponse -> onWhoResponse(msg)

            is InterEngineMessage.KickRequest -> {
                val targetSid = players.findSessionByName(msg.targetPlayerName) ?: return
                outbound.send(OutboundEvent.Close(targetSid, "Kicked by staff."))
            }

            is InterEngineMessage.ShutdownRequest -> {
                for (p in players.allPlayers()) {
                    outbound.send(
                        OutboundEvent.SendText(
                            p.sessionId,
                            "[SYSTEM] ${msg.initiatorName} has initiated a server shutdown. Goodbye!",
                        ),
                    )
                }
                onShutdown()
            }

            is InterEngineMessage.TransferRequest -> {
                val targetSid = players.findSessionByName(msg.targetPlayerName) ?: return
                val targetPlayer = players.get(targetSid) ?: return
                val targetRoomId = resolveRoomId(msg.targetRoomId, targetPlayer.roomId.zone) ?: return
                if (world.rooms.containsKey(targetRoomId)) {
                    players.moveTo(targetSid, targetRoomId)
                    outbound.send(OutboundEvent.SendText(targetSid, "You are transported by a divine hand."))
                    router.handle(targetSid, Command.Look)
                } else if (handoffManager != null) {
                    combatSystem.endCombatFor(targetSid)
                    regenSystem.onPlayerDisconnected(targetSid)
                    statusEffectSystem.removeAllFromPlayer(targetSid)
                    when (handoffManager.initiateHandoff(targetSid, targetRoomId)) {
                        is HandoffResult.Initiated -> Unit
                        HandoffResult.PlayerNotFound -> Unit
                        HandoffResult.AlreadyInTransit -> Unit
                        HandoffResult.NoEngineForZone -> {
                            outbound.send(
                                OutboundEvent.SendError(
                                    targetSid,
                                    "The transfer destination is not currently available.",
                                ),
                            )
                            outbound.send(OutboundEvent.SendPrompt(targetSid))
                        }
                    }
                }
            }

            is InterEngineMessage.SessionRedirect -> {
                logger.debug { "SessionRedirect received (handled at gateway layer)" }
            }
        }
    }
}
