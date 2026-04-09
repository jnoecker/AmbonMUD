package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GambleResult
import dev.ambon.engine.LotteryBuyResult
import dev.ambon.engine.LotterySystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class LotteryHandler(
    private val ctx: EngineContext,
    private val lotterySystem: LotterySystem? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.LotteryInfo> { sid, _ -> handleLotteryInfo(sid) }
        router.on<Command.LotteryBuy> { sid, cmd -> handleLotteryBuy(sid, cmd) }
        router.on<Command.Gamble> { sid, cmd -> handleGamble(sid, cmd) }
    }

    private suspend fun handleLotteryInfo(sessionId: SessionId) {
        val system = lotterySystem ?: return sendUnavailable(sessionId, "The lottery")

        players.withPlayer(sessionId) { me ->
            val info = system.getInfo(me.name)
            val timeLeft = (info.nextDrawingMs - System.currentTimeMillis()).coerceAtLeast(0)
            val minutesLeft = timeLeft / 60_000
            val secondsLeft = (timeLeft % 60_000) / 1_000

            outbound.send(OutboundEvent.SendInfo(sessionId, "[ Lottery ]"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Jackpot:      ${info.jackpot} gold"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Tickets sold: ${info.totalTickets}"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Your tickets: ${info.playerTickets}"))
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  Next drawing: ${minutesLeft}m ${secondsLeft}s",
                ),
            )
            outbound.send(
                OutboundEvent.SendInfo(sessionId, "  Use 'lottery buy [count]' to purchase tickets."),
            )

            emitLotteryGmcp(sessionId, me.name, system)
        }
    }

    private suspend fun handleLotteryBuy(sessionId: SessionId, cmd: Command.LotteryBuy) {
        val system = lotterySystem ?: return sendUnavailable(sessionId, "The lottery")

        players.withPlayer(sessionId) { me ->
            val result = system.buyTickets(
                playerName = me.name,
                sessionId = sessionId,
                count = cmd.count,
                currentGold = me.gold,
                deductGold = { cost -> me.gold -= cost },
            )

            when (result) {
                is LotteryBuyResult.Success -> {
                    val message =
                        "You purchased ${result.count} lottery ticket(s) for ${result.totalCost} gold. " +
                            "You now have ${result.totalTickets} ticket(s)."
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            message,
                        ),
                    )
                    sendLotteryFeedback(sessionId, "success", message, code = "PURCHASE_COMPLETE", command = "buy")
                    markVitalsDirty(sessionId)
                    emitLotteryGmcp(sessionId, me.name, system)
                }

                is LotteryBuyResult.InsufficientGold -> {
                    val message = "You need ${result.need} gold but only have ${result.have}."
                    outbound.send(OutboundEvent.SendError(sessionId, message))
                    sendLotteryFeedback(sessionId, "error", message, code = "INSUFFICIENT_GOLD", command = "buy")
                }

                is LotteryBuyResult.ExceedsLimit -> {
                    val message = "You already have ${result.current} ticket(s). Maximum is ${result.max} per drawing."
                    outbound.send(OutboundEvent.SendError(sessionId, message))
                    sendLotteryFeedback(sessionId, "error", message, code = "TICKET_LIMIT", command = "buy")
                }

                is LotteryBuyResult.Disabled -> {
                    sendUnavailable(sessionId, "The lottery")
                }
            }
        }
    }

    private suspend fun handleGamble(sessionId: SessionId, cmd: Command.Gamble) {
        val system = lotterySystem ?: return sendUnavailable(sessionId, "Gambling")

        players.withPlayer(sessionId) { me ->
            val room = ctx.world.rooms[me.roomId]
            val inTavern = room?.tavern == true

            val result = system.gamble(
                sessionId = sessionId,
                amount = cmd.amount,
                currentGold = me.gold,
                inTavern = inTavern,
            )

            when (result) {
                is GambleResult.Win -> {
                    me.gold = me.gold - result.bet + result.payout
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "You roll the dice... ${result.roll} (need ${result.needed} or less). You WIN!",
                        ),
                    )
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "You collect ${result.payout} gold! (Net gain: ${result.payout - result.bet} gold)",
                        ),
                    )
                    markVitalsDirty(sessionId)
                    broadcastToRoomExcept(
                        me.roomId,
                        sessionId,
                        "${me.name} rolls the dice and wins ${result.payout} gold!",
                        players,
                        outbound,
                    )
                }

                is GambleResult.Lose -> {
                    me.gold -= result.bet
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "You roll the dice... ${result.roll} (need ${result.needed} or less). You lose.",
                        ),
                    )
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "You lose ${result.bet} gold.",
                        ),
                    )
                    markVitalsDirty(sessionId)
                    broadcastToRoomExcept(
                        me.roomId,
                        sessionId,
                        "${me.name} rolls the dice and loses.",
                        players,
                        outbound,
                    )
                }

                is GambleResult.InsufficientGold -> {
                    outbound.send(
                        OutboundEvent.SendError(
                            sessionId,
                            "You need ${result.need} gold but only have ${result.have}.",
                        ),
                    )
                }

                is GambleResult.BetTooLow -> {
                    outbound.send(
                        OutboundEvent.SendError(
                            sessionId,
                            "Minimum bet is ${result.min} gold.",
                        ),
                    )
                }

                is GambleResult.BetTooHigh -> {
                    outbound.send(
                        OutboundEvent.SendError(
                            sessionId,
                            "Maximum bet is ${result.max} gold.",
                        ),
                    )
                }

                is GambleResult.OnCooldown -> {
                    val seconds = (result.remainingMs + 999) / 1000
                    outbound.send(
                        OutboundEvent.SendError(
                            sessionId,
                            "You need to wait ${seconds}s before gambling again.",
                        ),
                    )
                }

                is GambleResult.NotInTavern -> {
                    outbound.send(
                        OutboundEvent.SendError(
                            sessionId,
                            "You must be in a tavern to gamble.",
                        ),
                    )
                }

                is GambleResult.Disabled -> {
                    sendUnavailable(sessionId, "Gambling")
                }
            }
        }
    }

    private suspend fun emitLotteryGmcp(
        sessionId: SessionId,
        playerName: String,
        system: LotterySystem,
    ) {
        val info = system.getInfo(playerName)
        gmcpEmitter?.sendLotteryInfo(sessionId, info)
    }

    private suspend fun sendUnavailable(sessionId: SessionId, name: String) {
        val message = "$name is not available on this server."
        outbound.send(OutboundEvent.SendError(sessionId, message))
        sendLotteryFeedback(sessionId, "error", message, code = "UNAVAILABLE")
    }

    private suspend fun sendLotteryFeedback(
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
            scope = "lottery",
            command = command,
        )
    }
}
