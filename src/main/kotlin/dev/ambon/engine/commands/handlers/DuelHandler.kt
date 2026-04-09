package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.DuelSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class DuelHandler(
    private val ctx: EngineContext,
    private val duelSystem: DuelSystem? = null,
    private val combatSystem: CombatSystem? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Duel> { sid, cmd -> handleDuel(sid, cmd) }
        router.on<Command.DuelAccept> { sid, _ -> handleDuelAccept(sid) }
        router.on<Command.DuelDecline> { sid, _ -> handleDuelDecline(sid) }
    }

    private suspend fun handleDuel(sessionId: SessionId, cmd: Command.Duel) {
        val ds = duelSystem ?: return sendUnavailable(sessionId)

        players.withPlayer(sessionId) { me ->
            // Can't duel while in mob combat
            if (combatSystem?.isInCombat(sessionId) == true) {
                val message = "You cannot duel while in combat."
                outbound.send(OutboundEvent.SendError(sessionId, message))
                sendDuelFeedback(sessionId, "error", message, code = "IN_COMBAT", command = "duel")
                return
            }

            val targetSid = players.findSessionByName(cmd.targetPlayer)
            if (targetSid == null) {
                val message = "No such player: ${cmd.targetPlayer}"
                outbound.send(OutboundEvent.SendError(sessionId, message))
                sendDuelFeedback(sessionId, "error", message, code = "TARGET_NOT_FOUND", command = "duel")
                return
            }

            players.withPlayer(targetSid) { target ->
                if (target.roomId != me.roomId) {
                    val message = "${target.name} is not here."
                    outbound.send(OutboundEvent.SendError(sessionId, message))
                    sendDuelFeedback(sessionId, "error", message, code = "TARGET_NOT_NEARBY", command = "duel")
                    return
                }

                if (combatSystem?.isInCombat(targetSid) == true) {
                    val message = "${target.name} is in combat."
                    outbound.send(OutboundEvent.SendError(sessionId, message))
                    sendDuelFeedback(sessionId, "error", message, code = "TARGET_IN_COMBAT", command = "duel")
                    return
                }

                val error = ds.challenge(sessionId, targetSid)
                if (error != null) {
                    outbound.send(OutboundEvent.SendError(sessionId, error))
                    sendDuelFeedback(sessionId, "error", error, code = "CHALLENGE_REJECTED", command = "duel")
                    return
                }

                val message = "You challenge ${target.name} to a duel!"
                outbound.send(
                    OutboundEvent.SendInfo(sessionId, message),
                )
                sendDuelFeedback(sessionId, "success", message, code = "CHALLENGE_SENT", command = "duel")
                outbound.send(
                    OutboundEvent.SendInfo(
                        targetSid,
                        "${me.name} challenges you to a duel! Type 'duel accept' or 'duel decline'.",
                    ),
                )
                gmcpEmitter?.sendDuelChallenge(sessionId, me.name, target.name, "outgoing")
                gmcpEmitter?.sendDuelChallenge(targetSid, me.name, target.name, "incoming")
                for (p in players.playersInRoom(me.roomId)) {
                    if (p.sessionId != sessionId && p.sessionId != targetSid) {
                        outbound.send(OutboundEvent.SendText(p.sessionId, "${me.name} challenges ${target.name} to a duel!"))
                    }
                }
            }
        }
    }

    private suspend fun handleDuelAccept(sessionId: SessionId) {
        val ds = duelSystem ?: return sendUnavailable(sessionId)

        val challenge = ds.getPendingChallenge(sessionId)
        if (challenge == null) {
            val message = "No pending duel challenge to accept."
            outbound.send(OutboundEvent.SendError(sessionId, message))
            sendDuelFeedback(sessionId, "error", message, code = "NO_PENDING_CHALLENGE", command = "accept")
            return
        }

        // Re-validate: both players must still be in the same room and not in combat
        val me = players.get(sessionId)
        val challenger = players.get(challenge.challengerSid)
        if (me == null || challenger == null) {
            ds.decline(sessionId)
            val message = "The challenger is no longer available."
            outbound.send(OutboundEvent.SendError(sessionId, message))
            sendDuelFeedback(sessionId, "error", message, code = "CHALLENGER_UNAVAILABLE", command = "accept")
            return
        }
        if (me.roomId != challenger.roomId) {
            ds.decline(sessionId)
            val message = "You must be in the same room to duel."
            outbound.send(OutboundEvent.SendError(sessionId, message))
            sendDuelFeedback(sessionId, "error", message, code = "CHALLENGER_NOT_NEARBY", command = "accept")
            outbound.send(OutboundEvent.SendInfo(challenge.challengerSid, "${me.name} is no longer nearby. Duel cancelled."))
            return
        }
        if (combatSystem?.isInCombat(sessionId) == true || combatSystem?.isInCombat(challenge.challengerSid) == true) {
            ds.decline(sessionId)
            val message = "Cannot duel while in combat."
            outbound.send(OutboundEvent.SendError(sessionId, message))
            sendDuelFeedback(sessionId, "error", message, code = "IN_COMBAT", command = "accept")
            return
        }

        val duel = ds.accept(sessionId)
        if (duel == null) {
            val message = "No pending duel challenge to accept."
            outbound.send(OutboundEvent.SendError(sessionId, message))
            sendDuelFeedback(sessionId, "error", message, code = "NO_PENDING_CHALLENGE", command = "accept")
            return
        }

        outbound.send(
            OutboundEvent.SendInfo(duel.player1, "** ${me.name} accepts your duel challenge! Fight! **"),
        )
        outbound.send(
            OutboundEvent.SendInfo(duel.player2, "** You accept the duel with ${challenger.name}! Fight! **"),
        )
        sendDuelFeedback(
            duel.player1,
            "success",
            "${me.name} accepted your duel challenge.",
            code = "CHALLENGE_ACCEPTED",
            command = "accept",
        )
        sendDuelFeedback(
            duel.player2,
            "success",
            "You accept the duel with ${challenger.name}.",
            code = "CHALLENGE_ACCEPTED",
            command = "accept",
        )
        gmcpEmitter?.sendDuelState(duel.player1, active = true, opponentName = me.name, startedAtMs = duel.startedAtMs)
        gmcpEmitter?.sendDuelState(duel.player2, active = true, opponentName = challenger.name, startedAtMs = duel.startedAtMs)

        for (p in players.playersInRoom(me.roomId)) {
            if (p.sessionId != duel.player1 && p.sessionId != duel.player2) {
                outbound.send(OutboundEvent.SendText(p.sessionId, "** ${challenger.name} and ${me.name} begin a duel! **"))
            }
        }
    }

    private suspend fun handleDuelDecline(sessionId: SessionId) {
        val ds = duelSystem ?: return sendUnavailable(sessionId)

        val challenge = ds.decline(sessionId)
        if (challenge == null) {
            val message = "No pending duel challenge to decline."
            outbound.send(OutboundEvent.SendError(sessionId, message))
            sendDuelFeedback(sessionId, "error", message, code = "NO_PENDING_CHALLENGE", command = "decline")
            return
        }

        val myName = players.get(sessionId)?.name ?: "Someone"
        val message = "You decline the duel challenge."
        outbound.send(OutboundEvent.SendInfo(sessionId, message))
        sendDuelFeedback(sessionId, "success", message, code = "CHALLENGE_DECLINED", command = "decline")
        outbound.send(
            OutboundEvent.SendInfo(challenge.challengerSid, "$myName declined your duel challenge."),
        )
        gmcpEmitter?.sendDuelState(sessionId, active = false)
        gmcpEmitter?.sendDuelState(challenge.challengerSid, active = false)
    }

    private suspend fun sendUnavailable(sessionId: SessionId) {
        val message = "Dueling is not available."
        outbound.send(OutboundEvent.SendError(sessionId, message))
        sendDuelFeedback(sessionId, "error", message, code = "UNAVAILABLE")
    }

    private suspend fun sendDuelFeedback(
        sessionId: SessionId,
        type: String,
        message: String,
        code: String? = null,
        command: String? = null,
    ) {
        gmcpEmitter?.sendUiFeedback(
            sessionId = sessionId,
            type = type,
            message = message,
            code = code,
            scope = "duel",
            command = command,
        )
    }
}
