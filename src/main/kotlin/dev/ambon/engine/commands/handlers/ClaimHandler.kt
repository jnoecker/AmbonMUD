package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.ClaimResult
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ClaimHandler(
    ctx: EngineContext,
    /**
     * Returns the engine's coroutine scope. The handler dispatches the BCrypt
     * + DB write portion of `claim` here so the engine tick loop is not
     * blocked while a slow persistence backend completes.
     */
    private val getEngineScope: () -> CoroutineScope,
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

        // Off-tick: BCrypt and `repo.create` can be expensive (especially on
        // Postgres). Launch on the engine scope so the tick loop and inbound
        // draining keep moving while persistence completes. PlayerRegistry
        // re-validates state inside `claim`, so a disconnect mid-flight is
        // handled safely.
        getEngineScope().launch {
            handleClaimResult(sessionId, players.claim(sessionId, cmd.newName, cmd.password))
        }
    }

    private suspend fun handleClaimResult(sessionId: SessionId, result: ClaimResult) {
        when (result) {
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
            ClaimResult.Reserved -> outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "That name is reserved and cannot be used. Pick another with " +
                        "'claim <newname> <password>'.",
                ),
            )
        }
    }
}
