package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.RoomId
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LotteryHandler(
    private val ctx: EngineContext,
    private val lotterySystem: LotterySystem? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
    /** Engine scope for pacing the dice reveal off-tick; null skips the suspense. */
    private val getEngineScope: (() -> CoroutineScope)? = null,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.LotteryInfo> { sid, _ -> handleLotteryInfo(sid) }
        router.on<Command.LotteryBuy> { sid, cmd -> handleLotteryBuy(sid, cmd) }
        router.on<Command.Gamble> { sid, cmd -> handleGamble(sid, cmd) }
        router.on<Command.DiceRules> { sid, _ -> handleDiceRules(sid) }
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

        // Settle gold and the web animation immediately; the GMCP payload drives
        // the client's own sequenced reveal. The text feed is paced separately so
        // the telnet crowd feels the dice climb too.
        me.gold = me.gold - result.bet + result.payout
        markVitalsDirty(sessionId)

        val outcome = outcomeOf(result)
        emitGambleGmcp(sessionId, system, outcome, result)

        val net = result.payout - result.bet
        val name = me.name
        val roomId = me.roomId
        val scope = getEngineScope?.invoke()
        if (scope != null) {
            scope.launch { revealDice(sessionId, result, outcome, net, name, roomId, paced = true) }
        } else {
            revealDice(sessionId, result, outcome, net, name, roomId, paced = false)
        }
    }

    /**
     * Streams the roll to the text feed one child at a time, the running total
     * climbing with each, then the Luneqrae coin and the verdict. When [paced]
     * the lines are spaced a beat apart for suspense (run off-tick on the engine
     * scope); otherwise they flush instantly (no scope, e.g. tests).
     */
    private suspend fun revealDice(
        sessionId: SessionId,
        result: GambleResult.Resolved,
        outcome: String,
        net: Long,
        name: String,
        roomId: RoomId,
        paced: Boolean,
    ) {
        suspend fun beat() {
            if (paced) delay(REVEAL_BEAT_MS)
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, "You cast Aineroia's six children across the velvet..."))

        var running = 0
        for (die in result.dice) {
            beat()
            running += die.value
            val crown = if (die.isMax) " — its crown blazes!" else ""
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  ${dieName(die.kind)} (d${die.kind.sides}) settles on ${die.value}$crown   (total: $running)",
                ),
            )
        }

        beat()
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "  The dice lie still. Total ${result.sum} — you needed ${result.target} or less.",
            ),
        )

        if (result.coinFired) {
            beat()
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  ${result.maxCount} children wear their crowns — the Luneqrae coin leaps skyward...",
                ),
            )
        }

        beat()
        val outcomeLine = when (outcome) {
            "jackpot" ->
                "The Luneqrae coin lands true — and your sum held! You collect ${result.payout} gold! (Net +$net)"

            "coin" ->
                "Your sum overran, but the Luneqrae coin lands in your favour! " +
                    "You collect ${result.payout} gold! (Net +$net)"

            "win" -> "Fate smiles — you WIN! You collect ${result.payout} gold! (Net +$net)"
            else -> {
                val coinNote = if (result.coinFired) " The coin falls dark." else ""
                "The sum overruns the target.$coinNote You lose ${result.bet} gold."
            }
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, outcomeLine))

        val broadcast = when (outcome) {
            "jackpot", "coin" ->
                "$name rolls Aineroia's Dice and the Luneqrae coin blesses them with ${result.payout} gold!"

            "win" -> "$name rolls Aineroia's Dice and wins ${result.payout} gold!"
            else -> "$name rolls Aineroia's Dice and busts."
        }
        broadcastToRoomExcept(roomId, sessionId, broadcast, players, outbound)
    }

    private suspend fun handleDiceRules(sessionId: SessionId) {
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
            val info = system.getInfo(me.name)
            val lines = listOf(
                "[ Aineroia's Dice ]",
                "  Six dice — the children of the goddess Aineroia — are cast big to small:",
                "    Ophirae & Mycorae (d20), Pyrae & Aetherae (d16), Lustriae & Aureliae (d8).",
                "  Roll a total of ${info.diceWinTarget} or less to win ${formatMult(info.diceWinMultiplier)} your bet.",
                "  If ${info.coinMaxThreshold}+ dice land on their highest face, the Luneqrae coin flips:",
                "    a win pays ${formatMult(info.coinWinMultiplier)} on a bust, " +
                    "or ${formatMult(info.coinJackpotMultiplier)} if your total also held.",
                "  Bets: ${info.diceMinBet}–${info.diceMaxBet} gold. Play with 'dice <amount>' in a tavern.",
            )
            for (line in lines) {
                outbound.send(OutboundEvent.SendInfo(sessionId, line))
            }
        }
    }

    private fun formatMult(value: Double): String {
        val whole = value.toLong()
        return if (value == whole.toDouble()) "$whole×" else "$value×"
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

    private companion object {
        /** Beat between revealed dice/lines in the paced text feed. Six dice +
         *  coin + verdict stays under the default 5s gamble cooldown. */
        const val REVEAL_BEAT_MS = 480L
    }
}
