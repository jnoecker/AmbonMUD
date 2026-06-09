package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.DieKind
import dev.ambon.engine.GambleResult
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.LotteryBuyResult
import dev.ambon.engine.LotterySystem
import dev.ambon.engine.PlayerState
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
        val system =
            lotterySystem
                ?: return sendErrorWithFeedback(
                    sessionId,
                    outbound,
                    gmcpEmitter,
                    "The lottery is not available on this server.",
                    "lottery",
                    code = "UNAVAILABLE",
                )

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
                    "  Ticket cost:  ${info.ticketCost} gold (max ${info.maxTicketsPerPlayer} per drawing)",
                ),
            )
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
        val system =
            lotterySystem
                ?: return sendErrorWithFeedback(
                    sessionId,
                    outbound,
                    gmcpEmitter,
                    "The lottery is not available on this server.",
                    "lottery",
                    code = "UNAVAILABLE",
                )

        players.withPlayer(sessionId) { me ->
            val result = system.buyTickets(
                playerName = me.name,
                sessionId = sessionId,
                count = cmd.count,
                currentGold = me.gold,
                inTavern = ctx.world.rooms[me.roomId]?.tavern == true,
                deductGold = { cost -> me.gold -= cost },
            )

            when (result) {
                is LotteryBuyResult.Success -> {
                    ctx.metrics.onGameEvent("tavern", "lottery_ticket", count = result.count.toLong())
                    val message =
                        "You purchased ${result.count} lottery ticket(s) for ${result.totalCost} gold. " +
                            "You now have ${result.totalTickets} ticket(s)."
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            message,
                        ),
                    )
                    sendScopedFeedback(
                        sessionId,
                        gmcpEmitter,
                        "success",
                        message,
                        "lottery",
                        code = "PURCHASE_COMPLETE",
                        command = "buy",
                    )
                    markVitalsDirty(sessionId)
                    emitLotteryGmcp(sessionId, me.name, system)
                }

                is LotteryBuyResult.InsufficientGold -> {
                    val message = "You need ${result.need} gold but only have ${result.have}."
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        message,
                        "lottery",
                        code = "INSUFFICIENT_GOLD",
                        command = "buy",
                    )
                }

                is LotteryBuyResult.ExceedsLimit -> {
                    val message = "You already have ${result.current} ticket(s). Maximum is ${result.max} per drawing."
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        message,
                        "lottery",
                        code = "TICKET_LIMIT",
                        command = "buy",
                    )
                }

                is LotteryBuyResult.NotInTavern -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "You must be in a tavern to buy lottery tickets.",
                        "lottery",
                        code = "NOT_IN_TAVERN",
                        command = "buy",
                    )
                }

                is LotteryBuyResult.Disabled -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "The lottery is not available on this server.",
                        "lottery",
                        code = "UNAVAILABLE",
                    )
                }
            }
        }
    }

    private suspend fun handleGamble(sessionId: SessionId, cmd: Command.Gamble) {
        val system =
            lotterySystem
                ?: return sendErrorWithFeedback(
                    sessionId,
                    outbound,
                    gmcpEmitter,
                    "Gambling is not available on this server.",
                    "lottery",
                    code = "UNAVAILABLE",
                )

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
                is GambleResult.Resolved -> handleGambleResolved(sessionId, me, system, result)

                is GambleResult.InsufficientGold -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "You need ${result.need} gold but only have ${result.have}.",
                        "dice",
                        code = "INSUFFICIENT_GOLD",
                        command = "gamble",
                    )
                }

                is GambleResult.BetTooLow -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "Minimum bet is ${result.min} gold.",
                        "dice",
                        code = "BET_TOO_LOW",
                        command = "gamble",
                    )
                }

                is GambleResult.BetTooHigh -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "Maximum bet is ${result.max} gold.",
                        "dice",
                        code = "BET_TOO_HIGH",
                        command = "gamble",
                    )
                }

                is GambleResult.OnCooldown -> {
                    val seconds = (result.remainingMs + 999) / 1000
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "You need to wait ${seconds}s before gambling again.",
                        "dice",
                        code = "ON_COOLDOWN",
                        command = "gamble",
                    )
                }

                is GambleResult.NotInTavern -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "You must be in a tavern to gamble.",
                        "dice",
                        code = "NOT_IN_TAVERN",
                        command = "gamble",
                    )
                }

                is GambleResult.Disabled -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "Gambling is not available on this server.",
                        "lottery",
                        code = "UNAVAILABLE",
                    )
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

    private suspend fun handleGambleResolved(
        sessionId: SessionId,
        me: PlayerState,
        system: LotterySystem,
        result: GambleResult.Resolved,
    ) {
        ctx.metrics.onGameEvent("tavern", "gamble")
        if (result.won) ctx.metrics.onGameEvent("tavern", "gamble_win")
        if (result.coinFired) ctx.metrics.onGameEvent("tavern", "gamble_coin")

        me.gold = me.gold - result.bet + result.payout

        val rollLine = result.dice.joinToString(", ") { "${dieName(it.kind)} ${it.value}" }
        outbound.send(OutboundEvent.SendInfo(sessionId, "The children tumble across the velvet: $rollLine."))
        outbound.send(OutboundEvent.SendInfo(sessionId, "Total ${result.sum} (target ${result.target} or less)."))

        val net = result.payout - result.bet
        val outcome = outcomeOf(result)
        when (outcome) {
            "jackpot" -> outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "The Luneqrae coin spins skyward and lands true — and your sum held! " +
                        "You collect ${result.payout} gold! (Net +$net)",
                ),
            )

            "coin" -> outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "Your sum overran, but the Luneqrae coin flips in your favour! " +
                        "You collect ${result.payout} gold! (Net +$net)",
                ),
            )

            "win" -> outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "Fate smiles — you WIN! You collect ${result.payout} gold! (Net +$net)",
                ),
            )

            else -> {
                val coinNote = if (result.coinFired) " The Luneqrae coin falls dark." else ""
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "The sum overruns the target.$coinNote You lose ${result.bet} gold.",
                    ),
                )
            }
        }

        markVitalsDirty(sessionId)
        emitGambleGmcp(sessionId, system, outcome, result)

        val broadcast = when (outcome) {
            "jackpot", "coin" ->
                "${me.name} rolls Aineroira's Dice and the Luneqrae coin blesses them with ${result.payout} gold!"

            "win" -> "${me.name} rolls Aineroira's Dice and wins ${result.payout} gold!"
            else -> "${me.name} rolls Aineroira's Dice and busts."
        }
        broadcastToRoomExcept(me.roomId, sessionId, broadcast, players, outbound)
    }

    private fun dieName(kind: DieKind): String =
        kind.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun outcomeOf(result: GambleResult.Resolved): String =
        when {
            result.coinFired && result.coinWon && result.baseWin -> "jackpot"
            result.coinFired && result.coinWon -> "coin"
            result.baseWin -> "win"
            else -> "lose"
        }

    private suspend fun emitGambleGmcp(
        sessionId: SessionId,
        system: LotterySystem,
        outcome: String,
        result: GambleResult.Resolved,
    ) {
        gmcpEmitter?.sendGambleResult(
            sessionId,
            GmcpEmitter.GambleResultPayload(
                outcome = outcome,
                bet = result.bet,
                payout = result.payout,
                multiplier = result.multiplier,
                dice = result.dice.map {
                    GmcpEmitter.GambleDiePayload(
                        kind = it.kind.name.lowercase(),
                        sides = it.kind.sides,
                        value = it.value,
                        isMax = it.isMax,
                    )
                },
                sum = result.sum,
                target = result.target,
                maxCount = result.maxCount,
                coinFired = result.coinFired,
                coinWon = result.coinWon,
                cooldownMs = system.diceCooldownMs,
            ),
        )
    }
}
