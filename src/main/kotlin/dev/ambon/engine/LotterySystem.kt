package dev.ambon.engine

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.config.GamblingConfig
import dev.ambon.config.LotteryConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.persistence.atomicWriteText
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.random.Random

private val log = KotlinLogging.logger {}

/** Snapshot of current lottery state for display purposes. */
data class LotteryInfo(
    val jackpot: Long,
    val totalTickets: Int,
    val nextDrawingMs: Long,
    val playerTickets: Int,
    val ticketCost: Long,
    val maxTicketsPerPlayer: Int,
    val diceMinBet: Long,
    val diceMaxBet: Long,
    val diceWinMultiplier: Double,
    /** Aineroira's Dice: a summed roll at or below this target wins the base payout. */
    val diceWinTarget: Int,
    /** This many dice (or more) on their max face summon the Luneqrae coin flip. */
    val coinMaxThreshold: Int,
    /** Payout multiplier when the coin flip wins after a busted sum. */
    val coinWinMultiplier: Double,
    /** Payout multiplier when the coin flip wins and the sum stayed at or below target. */
    val coinJackpotMultiplier: Double,
    val diceCooldownMs: Long,
)

/**
 * The six children of Aineroira, rolled in descending size. Each pair shares a
 * die size; the order here is the order they tumble onto the table.
 */
enum class DieKind(
    val sides: Int,
) {
    /** Large pair — whalebone eastern dragon. */
    OPHIRAE(20),

    /** Large pair — moss-green fungal bloom. */
    MYCORAE(20),

    /** Medium pair — ember-red phoenix. */
    PYRAE(16),

    /** Medium pair — obsidian shadow-glass cloak (born of her shadow, not her children). */
    AETHERAE(16),

    /** Small pair — carved-wood forest faerie. */
    LUSTRIAE(8),

    /** Small pair — carved-coral jellyfish. */
    AURELIAE(8),
}

/** One settled die: which child, what it showed, and whether it crowned its max face. */
data class DieRoll(
    val kind: DieKind,
    val value: Int,
    val isMax: Boolean,
)

/** Result of a lottery ticket purchase attempt. */
sealed interface LotteryBuyResult {
    data class Success(
        val count: Int,
        val totalCost: Long,
        val totalTickets: Int,
    ) : LotteryBuyResult

    data class InsufficientGold(
        val have: Long,
        val need: Long,
    ) : LotteryBuyResult

    data class ExceedsLimit(
        val current: Int,
        val max: Int,
    ) : LotteryBuyResult

    data object NotInTavern : LotteryBuyResult

    data object Disabled : LotteryBuyResult
}

/** Result of a lottery drawing. */
data class LotteryDrawingResult(
    val winnerName: String?,
    val winnerSid: SessionId?,
    val jackpotAmount: Long,
    val totalTickets: Int,
)

/** Result of a gamble attempt. */
sealed interface GambleResult {
    /**
     * A full resolution of Aineroira's Dice: the six dice in roll order, their
     * sum against the target, and the optional Luneqrae coin flip.
     *
     * @param payout total gold returned (0 on a clean loss); net = payout - bet.
     * @param multiplier the multiplier applied (0, base, coin, or jackpot).
     */
    data class Resolved(
        val bet: Long,
        val payout: Long,
        val multiplier: Double,
        val dice: List<DieRoll>,
        val sum: Int,
        val target: Int,
        val maxCount: Int,
        val coinFired: Boolean,
        val coinWon: Boolean,
    ) : GambleResult {
        /** Whether the player came out ahead (any payout). */
        val won: Boolean get() = payout > 0L

        /** Whether the summed roll itself landed at or below the target. */
        val baseWin: Boolean get() = sum <= target
    }

    data class InsufficientGold(
        val have: Long,
        val need: Long,
    ) : GambleResult

    data class BetTooLow(
        val min: Long,
    ) : GambleResult

    data class BetTooHigh(
        val max: Long,
    ) : GambleResult

    data class OnCooldown(
        val remainingMs: Long,
    ) : GambleResult

    data object NotInTavern : GambleResult

    data object Disabled : GambleResult
}

/** JSON DTO for persisting lottery state. */
private data class PersistedLotteryState(
    val jackpot: Long = 0L,
    val nextDrawingMs: Long = 0L,
    val tickets: Map<String, Int> = emptyMap(),
    val ticketSessions: Map<String, Long> = emptyMap(),
)

/**
 * Manages the lottery (periodic drawings with accumulating jackpots)
 * and simple dice gambling at tavern rooms.
 */
class LotterySystem(
    private val lotteryConfig: LotteryConfig,
    private val gamblingConfig: GamblingConfig,
    private val clock: Clock,
    private val persistPath: Path? = null,
    private val random: Random = Random.Default,
) {
    private var jackpot: Long = lotteryConfig.jackpotSeedGold
    private var nextDrawingMs: Long = clock.millis() + lotteryConfig.drawingIntervalMs

    /** Player name (lowercase) -> ticket count. */
    private val tickets = mutableMapOf<String, Int>()

    /** Player name (lowercase) -> session id (for awarding winnings). */
    private val ticketSessions = mutableMapOf<String, SessionId>()

    /** Session id -> last gamble timestamp. */
    private val lastGambleTime = mutableMapOf<SessionId, Long>()

    /** The six children, in the order they tumble onto the table (big → small). */
    private val rollOrder = DieKind.entries

    /** Cooldown between dice rolls; exposed for GMCP gamble payloads. */
    val diceCooldownMs: Long get() = gamblingConfig.cooldownMs

    /** Returns lottery info for a specific player. */
    fun getInfo(playerName: String): LotteryInfo {
        val totalTickets = tickets.values.sum()
        val playerTickets = tickets[playerName.lowercase()] ?: 0
        return LotteryInfo(
            jackpot = jackpot,
            totalTickets = totalTickets,
            nextDrawingMs = nextDrawingMs,
            playerTickets = playerTickets,
            ticketCost = lotteryConfig.ticketCost,
            maxTicketsPerPlayer = lotteryConfig.maxTicketsPerPlayer,
            diceMinBet = gamblingConfig.diceMinBet,
            diceMaxBet = gamblingConfig.diceMaxBet,
            diceWinMultiplier = gamblingConfig.diceWinMultiplier,
            diceWinTarget = gamblingConfig.diceWinTarget,
            coinMaxThreshold = gamblingConfig.coinMaxThreshold,
            coinWinMultiplier = gamblingConfig.coinWinMultiplier,
            coinJackpotMultiplier = gamblingConfig.coinJackpotMultiplier,
            diceCooldownMs = gamblingConfig.cooldownMs,
        )
    }

    /**
     * Attempts to buy lottery tickets for a player.
     *
     * @param playerName the player's name
     * @param sessionId the player's session
     * @param count number of tickets to buy
     * @param currentGold player's current gold
     * @param inTavern whether the player is in a tavern room
     * @param deductGold callback to deduct gold from the player
     */
    fun buyTickets(
        playerName: String,
        sessionId: SessionId,
        count: Int,
        currentGold: Long,
        inTavern: Boolean,
        deductGold: (Long) -> Unit,
    ): LotteryBuyResult {
        if (!lotteryConfig.enabled) return LotteryBuyResult.Disabled
        if (!inTavern) return LotteryBuyResult.NotInTavern

        val key = playerName.lowercase()
        val current = tickets[key] ?: 0
        if (current + count > lotteryConfig.maxTicketsPerPlayer) {
            return LotteryBuyResult.ExceedsLimit(current, lotteryConfig.maxTicketsPerPlayer)
        }

        val totalCost = lotteryConfig.ticketCost * count
        if (currentGold < totalCost) {
            return LotteryBuyResult.InsufficientGold(currentGold, totalCost)
        }

        deductGold(totalCost)
        tickets[key] = current + count
        ticketSessions[key] = sessionId

        // Add percentage of ticket sales to jackpot
        val jackpotContribution = (totalCost * lotteryConfig.jackpotPercentFromTickets) / 100
        jackpot += jackpotContribution

        log.debug { "$playerName bought $count lottery ticket(s) for $totalCost gold (jackpot now $jackpot)" }
        persist()
        return LotteryBuyResult.Success(count, totalCost, current + count)
    }

    /**
     * Checks if a drawing should occur based on the current time.
     * Returns a [LotteryDrawingResult] if a drawing happened, null otherwise.
     */
    fun tick(nowMs: Long): LotteryDrawingResult? {
        if (!lotteryConfig.enabled) return null
        if (nowMs < nextDrawingMs) return null

        val result = performDrawing()
        nextDrawingMs = nowMs + lotteryConfig.drawingIntervalMs
        persist()
        return result
    }

    private fun performDrawing(): LotteryDrawingResult {
        val totalTickets = tickets.values.sum()
        if (totalTickets == 0) {
            log.debug { "Lottery drawing: no tickets sold, jackpot rolls over ($jackpot gold)" }
            return LotteryDrawingResult(
                winnerName = null,
                winnerSid = null,
                jackpotAmount = jackpot,
                totalTickets = 0,
            )
        }

        // Build weighted pool and pick winner
        val pool = mutableListOf<String>()
        for ((name, count) in tickets) {
            repeat(count) { pool.add(name) }
        }
        val winnerKey = pool[random.nextInt(pool.size)]
        val winnerSid = ticketSessions[winnerKey]
        val wonAmount = jackpot

        log.info { "Lottery drawing: $winnerKey wins $wonAmount gold ($totalTickets tickets sold)" }

        val result = LotteryDrawingResult(
            winnerName = winnerKey,
            winnerSid = winnerSid,
            jackpotAmount = wonAmount,
            totalTickets = totalTickets,
        )

        // Reset for next drawing
        tickets.clear()
        ticketSessions.clear()
        jackpot = lotteryConfig.jackpotSeedGold

        return result
    }

    /**
     * Attempts a dice gamble for a player.
     *
     * @param sessionId the player's session
     * @param amount the bet amount
     * @param currentGold player's current gold
     * @param inTavern whether the player is in a tavern room
     */
    fun gamble(
        sessionId: SessionId,
        amount: Long,
        currentGold: Long,
        inTavern: Boolean,
    ): GambleResult {
        if (!gamblingConfig.enabled) return GambleResult.Disabled
        if (!inTavern) return GambleResult.NotInTavern

        if (amount < gamblingConfig.diceMinBet) return GambleResult.BetTooLow(gamblingConfig.diceMinBet)
        if (amount > gamblingConfig.diceMaxBet) return GambleResult.BetTooHigh(gamblingConfig.diceMaxBet)
        if (currentGold < amount) return GambleResult.InsufficientGold(currentGold, amount)

        val now = clock.millis()
        val last = lastGambleTime[sessionId]
        if (last != null && (now - last) < gamblingConfig.cooldownMs) {
            return GambleResult.OnCooldown(gamblingConfig.cooldownMs - (now - last))
        }

        lastGambleTime[sessionId] = now

        // Roll the six children in order, big → small. Each die crowns its max
        // face if it shows its highest pip.
        val dice = rollOrder.map { kind ->
            val value = random.nextInt(kind.sides) + 1
            DieRoll(kind = kind, value = value, isMax = value == kind.sides)
        }
        val sum = dice.sumOf { it.value }
        val target = gamblingConfig.diceWinTarget
        val baseWin = sum <= target
        val maxCount = dice.count { it.isMax }

        // The Luneqrae coin flips whenever enough children crown their max face —
        // win or bust, fate decides. A winning flip rescues a bust into a 10×, or
        // gilds an already-winning sum into a 12×. A losing flip leaves the base
        // result untouched.
        val coinFired = maxCount >= gamblingConfig.coinMaxThreshold
        val coinWon = coinFired && random.nextInt(2) == 0

        val multiplier: Double =
            when {
                coinFired && coinWon && baseWin -> gamblingConfig.coinJackpotMultiplier
                coinFired && coinWon -> gamblingConfig.coinWinMultiplier
                baseWin -> gamblingConfig.diceWinMultiplier
                else -> 0.0
            }
        val payout = (amount * multiplier).toLong()

        return GambleResult.Resolved(
            bet = amount,
            payout = payout,
            multiplier = multiplier,
            dice = dice,
            sum = sum,
            target = target,
            maxCount = maxCount,
            coinFired = coinFired,
            coinWon = coinWon,
        )
    }

    /** Clears gamble cooldown for a disconnected player. */
    fun onDisconnect(sessionId: SessionId) {
        lastGambleTime.remove(sessionId)
    }

    /** Updates the session id mapping when a player reconnects. */
    fun updateSession(playerName: String, sessionId: SessionId) {
        val key = playerName.lowercase()
        if (key in tickets) {
            ticketSessions[key] = sessionId
        }
    }

    /** Loads persisted lottery state from disk. */
    fun loadPersistedState() {
        val path = persistPath ?: return
        if (!Files.exists(path)) return
        try {
            val mapper = jacksonObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val persisted: PersistedLotteryState = mapper.readValue(Files.readString(path))
            jackpot = persisted.jackpot
            nextDrawingMs = persisted.nextDrawingMs
            tickets.clear()
            tickets.putAll(persisted.tickets)
            ticketSessions.clear()
            for ((name, sidVal) in persisted.ticketSessions) {
                ticketSessions[name] = SessionId(sidVal)
            }
            log.info { "Loaded persisted lottery state: jackpot=$jackpot, tickets=${tickets.values.sum()}" }
        } catch (e: Exception) {
            log.warn(e) { "Failed to load lottery state from $path" }
        }
    }

    private fun persist() {
        val path = persistPath ?: return
        try {
            val mapper = jacksonObjectMapper()
            val persisted = PersistedLotteryState(
                jackpot = jackpot,
                nextDrawingMs = nextDrawingMs,
                tickets = tickets.toMap(),
                ticketSessions = ticketSessions.mapValues { it.value.value },
            )
            atomicWriteText(path, mapper.writeValueAsString(persisted))
        } catch (e: Exception) {
            log.warn(e) { "Failed to persist lottery state to $path" }
        }
    }

    /** Resets all state (for testing). */
    fun clear() {
        tickets.clear()
        ticketSessions.clear()
        lastGambleTime.clear()
        jackpot = lotteryConfig.jackpotSeedGold
        nextDrawingMs = clock.millis() + lotteryConfig.drawingIntervalMs
    }
}
