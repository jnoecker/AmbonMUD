package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PrestigeSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class PrestigeHandler(
    ctx: EngineContext,
    private val prestigeSystem: PrestigeSystem,
    private val progression: PlayerProgression,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Prestige> { sid, _ -> handlePrestige(sid) }
        router.on<Command.PrestigeInfo> { sid, _ -> handlePrestigeInfo(sid) }
    }

    private suspend fun handlePrestige(sessionId: SessionId) {
        if (!prestigeSystem.isEnabled()) {
            val message = "The prestige system is not enabled."
            outbound.send(OutboundEvent.SendError(sessionId, message))
            sendPrestigeFeedback(sessionId, "error", message, code = "UNAVAILABLE", command = "prestige")
            return
        }
        players.withPlayer(sessionId) { me ->
            val maxLevel = progression.maxLevel
            if (me.level < maxLevel) {
                val message = "You must reach level $maxLevel before you can prestige."
                outbound.send(OutboundEvent.SendError(sessionId, message))
                sendPrestigeFeedback(sessionId, "error", message, code = "LEVEL_REQUIRED", command = "prestige")
                return
            }
            if (me.prestigeLevel >= prestigeSystem.maxRank) {
                val message = "You have already reached the maximum prestige rank."
                outbound.send(OutboundEvent.SendError(sessionId, message))
                sendPrestigeFeedback(sessionId, "error", message, code = "MAX_RANK", command = "prestige")
                return
            }
            val cost = prestigeSystem.xpCostForNextRank(me.prestigeLevel)
            val available = prestigeSystem.availableXp(me)
            if (available < cost) {
                val message =
                    "You need ${"%,d".format(cost)} surplus XP to prestige (you have ${"%,d".format(available)})."
                outbound.send(OutboundEvent.SendError(sessionId, message))
                sendPrestigeFeedback(sessionId, "error", message, code = "INSUFFICIENT_XP", command = "prestige")
                return
            }

            val result = prestigeSystem.prestige(me, maxLevel) ?: return
            val perkDesc = result.perk?.description ?: "No perk"
            val message = "You have achieved Prestige Rank ${result.newRank}! Perk: $perkDesc"
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    message,
                ),
            )
            sendPrestigeFeedback(sessionId, "success", message, code = "PRESTIGE_COMPLETE", command = "prestige")

            // Announce to the room
            val roomId = me.roomId
            for (other in players.playersInRoom(roomId).filter { it.sessionId != sessionId }) {
                outbound.send(
                    OutboundEvent.SendText(
                        other.sessionId,
                        "${me.name} achieves Prestige Rank ${result.newRank}!",
                    ),
                )
            }

            // Persist and emit GMCP
            players.persistPlayer(sessionId)
            gmcpEmitter?.sendCharVitals(sessionId, me)
            gmcpEmitter?.sendPrestigeInfoForPlayer(sessionId, me)
        }
    }

    private suspend fun handlePrestigeInfo(sessionId: SessionId) {
        if (!prestigeSystem.isEnabled()) {
            val message = "The prestige system is not enabled."
            outbound.send(OutboundEvent.SendError(sessionId, message))
            sendPrestigeFeedback(sessionId, "error", message, code = "UNAVAILABLE", command = "info")
            return
        }
        players.withPlayer(sessionId) { me ->
            val maxLevel = progression.maxLevel
            val currentRank = me.prestigeLevel
            val maxRank = prestigeSystem.maxRank

            outbound.send(OutboundEvent.SendInfo(sessionId, "=== Prestige Status ==="))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Rank: $currentRank / $maxRank"))

            if (me.level < maxLevel) {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  Reach level $maxLevel to unlock prestige.",
                    ),
                )
            } else if (currentRank < maxRank) {
                val available = prestigeSystem.availableXp(me)
                val cost = prestigeSystem.xpCostForNextRank(currentRank)
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  Surplus XP: ${"%,d".format(available)}  |  Next rank cost: ${"%,d".format(cost)}",
                    ),
                )
            } else {
                outbound.send(OutboundEvent.SendInfo(sessionId, "  Maximum prestige rank achieved!"))
            }

            outbound.send(OutboundEvent.SendInfo(sessionId, ""))
            outbound.send(OutboundEvent.SendInfo(sessionId, "Perks:"))
            for (rank in 1..maxRank) {
                val perk = prestigeSystem.perkForRank(rank)
                val desc = perk?.description ?: "—"
                val earned = if (rank <= currentRank) "[*]" else "[ ]"
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  $earned Rank $rank: $desc",
                    ),
                )
            }

            // Emit structured GMCP
            val available = if (me.level >= maxLevel) prestigeSystem.availableXp(me) else 0L
            val nextCost = if (currentRank < maxRank) prestigeSystem.xpCostForNextRank(currentRank) else null
            gmcpEmitter?.sendPrestigeInfo(
                sessionId,
                enabled = true,
                currentRank = currentRank,
                maxRank = maxRank,
                availableXp = available,
                nextRankCost = nextCost,
                perks = gmcpEmitter.buildPrestigePerkPayloads(currentRank, maxRank) { prestigeSystem.perkForRank(it) },
            )
            sendPrestigeFeedback(sessionId, "info", "Prestige details refreshed.", code = "INFO_REFRESHED", command = "info")
        }
    }

    private suspend fun sendPrestigeFeedback(
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
            scope = "prestige",
            command = command,
        )
    }
}
