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
                outbound.send(OutboundEvent.SendError(sessionId, "You cannot duel while in combat."))
                return
            }

            val targetSid = requirePlayerOnline(sessionId, cmd.targetPlayer, players, outbound) ?: return

            players.withPlayer(targetSid) { target ->
                if (!requireSameRoom(sessionId, me, target, outbound)) return

                if (combatSystem?.isInCombat(targetSid) == true) {
                    outbound.send(OutboundEvent.SendError(sessionId, "${target.name} is in combat."))
                    return
                }

                val error = ds.challenge(sessionId, targetSid)
                if (error != null) {
                    outbound.send(OutboundEvent.SendError(sessionId, error))
                    return
                }

                outbound.send(
                    OutboundEvent.SendInfo(sessionId, "You challenge ${target.name} to a duel!"),
                )
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
            outbound.send(OutboundEvent.SendError(sessionId, "No pending duel challenge to accept."))
            return
        }

        // Re-validate: both players must still be in the same room and not in combat
        val me = players.get(sessionId)
        val challenger = players.get(challenge.challengerSid)
        if (me == null || challenger == null) {
            ds.decline(sessionId)
            outbound.send(OutboundEvent.SendError(sessionId, "The challenger is no longer available."))
            return
        }
        if (me.roomId != challenger.roomId) {
            ds.decline(sessionId)
            outbound.send(OutboundEvent.SendError(sessionId, "You must be in the same room to duel."))
            outbound.send(OutboundEvent.SendInfo(challenge.challengerSid, "${me.name} is no longer nearby. Duel cancelled."))
            return
        }
        if (combatSystem?.isInCombat(sessionId) == true || combatSystem?.isInCombat(challenge.challengerSid) == true) {
            ds.decline(sessionId)
            outbound.send(OutboundEvent.SendError(sessionId, "Cannot duel while in combat."))
            return
        }

        val duel = ds.accept(sessionId)
        if (duel == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "No pending duel challenge to accept."))
            return
        }

        outbound.send(
            OutboundEvent.SendInfo(duel.player1, "** ${me.name} accepts your duel challenge! Fight! **"),
        )
        outbound.send(
            OutboundEvent.SendInfo(duel.player2, "** You accept the duel with ${challenger.name}! Fight! **"),
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
            outbound.send(OutboundEvent.SendError(sessionId, "No pending duel challenge to decline."))
            return
        }

        val myName = players.get(sessionId)?.name ?: "Someone"
        outbound.send(OutboundEvent.SendInfo(sessionId, "You decline the duel challenge."))
        outbound.send(
            OutboundEvent.SendInfo(challenge.challengerSid, "$myName declined your duel challenge."),
        )
        gmcpEmitter?.sendDuelState(sessionId, active = false)
        gmcpEmitter?.sendDuelState(challenge.challengerSid, active = false)
    }

    private suspend fun sendUnavailable(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendError(sessionId, "Dueling is not available."))
    }
}
