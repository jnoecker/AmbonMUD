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
            outbound.send(OutboundEvent.SendError(sessionId, "The prestige system is not enabled."))
            return
        }
        players.withPlayer(sessionId) { me ->
            val maxLevel = progression.maxLevel
            if (me.level < maxLevel) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You must reach level $maxLevel before you can prestige.",
                    ),
                )
                return
            }
            if (me.prestigeLevel >= prestigeSystem.maxRank) {
                outbound.send(OutboundEvent.SendError(sessionId, "You have already reached the maximum prestige rank."))
                return
            }
            val cost = prestigeSystem.xpCostForNextRank(me.prestigeLevel)
            val available = prestigeSystem.availableXp(me)
            if (available < cost) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You need ${"%,d".format(cost)} surplus XP to prestige (you have ${"%,d".format(available)}).",
                    ),
                )
                return
            }

            val result = prestigeSystem.prestige(me, maxLevel) ?: return
            val perkDesc = result.perk?.description ?: "No perk"
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "You have achieved Prestige Rank ${result.newRank}! Perk: $perkDesc",
                ),
            )

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
        }
    }

    private suspend fun handlePrestigeInfo(sessionId: SessionId) {
        if (!prestigeSystem.isEnabled()) {
            outbound.send(OutboundEvent.SendError(sessionId, "The prestige system is not enabled."))
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
        }
    }
}
