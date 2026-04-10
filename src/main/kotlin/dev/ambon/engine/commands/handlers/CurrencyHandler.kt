package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.CurrencySystem
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.PlayerState
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class CurrencyHandler(
    ctx: EngineContext,
    private val currencySystem: CurrencySystem,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Currencies> { sid, _ -> handleCurrencies(sid) }
    }

    private suspend fun handleCurrencies(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val definitions = currencySystem.definitions()
            if (definitions.isEmpty()) {
                val message = "No secondary currencies exist."
                outbound.send(OutboundEvent.SendInfo(sessionId, message))
                sendScopedFeedback(
                    sessionId,
                    gmcpEmitter,
                    "info",
                    message,
                    "currencies",
                    code = "NONE_AVAILABLE",
                    command = "currencies",
                )
                return
            }

            outbound.send(OutboundEvent.SendInfo(sessionId, "[ Currencies ]"))
            for ((currencyId, def) in definitions) {
                val balance = currencySystem.balance(me, currencyId)
                val abbr = if (def.abbreviation.isNotEmpty()) " (${def.abbreviation})" else ""
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  ${def.displayName}$abbr: $balance",
                    ),
                )
            }

            emitCurrencies(sessionId, me)
            sendScopedFeedback(
                sessionId,
                gmcpEmitter,
                "info",
                "Wallet balances refreshed.",
                "currencies",
                code = "INFO_REFRESHED",
                command = "currencies",
            )
        }
    }

    internal suspend fun emitCurrencies(
        sessionId: SessionId,
        player: PlayerState,
    ) {
        val definitions = currencySystem.definitions()
        val payload = definitions.map { (currencyId, def) ->
            GmcpEmitter.CurrencyBalancePayload(
                id = currencyId,
                name = def.displayName,
                abbreviation = def.abbreviation,
                balance = currencySystem.balance(player, currencyId),
            )
        }
        gmcpEmitter?.sendCharCurrencies(sessionId, payload)
    }
}
