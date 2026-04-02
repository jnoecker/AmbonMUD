package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.ReputationSystem
import dev.ambon.engine.StandingTier
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class ReputationHandler(
    ctx: EngineContext,
    private val reputationSystem: ReputationSystem? = null,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Reputation> { sid, _ -> handleReputation(sid) }
    }

    private suspend fun handleReputation(sessionId: SessionId) {
        val rep = reputationSystem
        if (rep == null) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "Factions are not available."))
            return
        }

        players.withPlayer(sessionId) { me ->
            val standings = rep.allStandings(me)
            val definitions = rep.factionDefinitions()

            if (definitions.isEmpty()) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "No factions exist in this world."))
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
                val tier = StandingTier.forReputation(reputation)
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  %-20s %+8d  %-12s".format(def.name, reputation, tier.displayName),
                    ),
                )
            }

            emitFactions(sessionId, me, rep)
        }
    }

    internal suspend fun emitFactions(
        sessionId: SessionId,
        player: dev.ambon.engine.PlayerState,
        rep: ReputationSystem,
    ) {
        val definitions = rep.factionDefinitions()
        val standings = rep.allStandings(player)
        val payload = standings.map { (factionId, reputation) ->
            val def = definitions[factionId]
            GmcpEmitter.FactionStandingPayload(
                id = factionId,
                name = def?.name ?: factionId,
                reputation = reputation,
                tier = StandingTier.forReputation(reputation).displayName,
            )
        }
        gmcpEmitter?.sendCharFactions(sessionId, payload)
    }
}
