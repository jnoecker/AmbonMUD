package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.ReputationSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class ReputationHandler(
    ctx: EngineContext,
    private val reputationSystem: ReputationSystem,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Reputation> { sid, _ -> handleReputation(sid) }
    }

    private suspend fun handleReputation(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val standings = reputationSystem.allStandings(me)
            val definitions = reputationSystem.factionDefinitions()

            if (definitions.isEmpty()) {
                val message = "No factions exist in this world."
                outbound.send(OutboundEvent.SendInfo(sessionId, message))
                sendScopedFeedback(
                    sessionId,
                    gmcpEmitter,
                    "info",
                    message,
                    "factions",
                    code = "NONE_AVAILABLE",
                    command = "reputation",
                )
                return
            }

            outbound.send(OutboundEvent.SendInfo(sessionId, "[ Faction Standings ]"))
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  %-20s %8s  %-12s".format("Faction", "Rep", "Standing"),
                ),
            )
            for ((factionId, reputation) in standings) {
                val def = definitions[factionId] ?: continue
                val tierLabel = reputationSystem.tierLabel(reputation)
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  %-20s %+8d  %-12s".format(def.name, reputation, tierLabel),
                    ),
                )
            }

            emitFactions(sessionId, me)
            sendScopedFeedback(
                sessionId,
                gmcpEmitter,
                "info",
                "Faction standings refreshed.",
                "factions",
                code = "INFO_REFRESHED",
                command = "reputation",
            )
        }
    }

    internal suspend fun emitFactions(
        sessionId: SessionId,
        player: dev.ambon.engine.PlayerState,
    ) {
        val definitions = reputationSystem.factionDefinitions()
        val standings = reputationSystem.allStandings(player)
        val payload = standings.map { (factionId, reputation) ->
            val def = definitions[factionId]
            GmcpEmitter.FactionStandingPayload(
                id = factionId,
                name = def?.name ?: factionId,
                reputation = reputation,
                tier = reputationSystem.tierLabel(reputation),
            )
        }
        gmcpEmitter?.sendCharFactions(sessionId, payload)
    }
}
