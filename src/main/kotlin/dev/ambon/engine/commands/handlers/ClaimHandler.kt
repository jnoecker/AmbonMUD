package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.ClaimResult
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class ClaimHandler(
    ctx: EngineContext,
    private val onClaimed: suspend (SessionId) -> Unit = {},
) : CommandHandler {
    private val outbound = ctx.outbound
    private val players = ctx.players
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Claim> { sid, cmd -> handleClaim(sid, cmd) }
    }

    private suspend fun handleClaim(sessionId: SessionId, cmd: Command.Claim) {
        val me = players.get(sessionId) ?: return
        if (me.playerId != null) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "Your character is already saved. There's nothing to claim.",
                ),
            )
            return
        }

        when (val result = players.claim(sessionId, cmd.newName, cmd.password)) {
            is ClaimResult.Ok -> {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "Your character has been saved as ${result.name}. " +
                            "You can log in again later with this name and password.",
                    ),
                )
                val refreshed = players.get(sessionId)
                if (refreshed != null) {
                    gmcpEmitter?.sendCharName(sessionId, refreshed)
                }
                onClaimed(sessionId)
            }
            ClaimResult.NotDemo -> outbound.send(
                OutboundEvent.SendError(sessionId, "Your character is already saved."),
            )
            ClaimResult.InvalidName -> outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "Invalid name. Use 2-16 chars: letters/digits/_ and cannot start with digit.",
                ),
            )
            ClaimResult.InvalidPassword -> outbound.send(
                OutboundEvent.SendError(sessionId, "Invalid password. Use 1-72 chars."),
            )
            ClaimResult.Taken -> outbound.send(
                OutboundEvent.SendError(sessionId, "That name is already taken."),
            )
        }
    }
}
