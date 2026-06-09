package dev.ambon.engine

import dev.ambon.config.GamblingConfig
import dev.ambon.config.LotteryConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.random.Random

class LotterySystemTest {
    private val clock = MutableClock(10_000L)
    private val sid1 = SessionId(1L)
    private val sid2 = SessionId(2L)

    private val lotteryConfig = LotteryConfig(
        enabled = true,
        ticketCost = 100L,
        drawingIntervalMs = 60_000L,
        jackpotSeedGold = 500L,
        jackpotPercentFromTickets = 80,
        maxTicketsPerPlayer = 5,
    )

    private val gamblingConfig = GamblingConfig(
        enabled = true,
        diceMinBet = 10L,
        diceMaxBet = 1000L,
        diceWinMultiplier = 2.0,
        diceWinTarget = 45,
        coinMaxThreshold = 3,
        coinWinMultiplier = 10.0,
        coinJackpotMultiplier = 12.0,
        cooldownMs = 5_000L,
    )

    private lateinit var system: LotterySystem

    @BeforeEach
    fun setUp() {
        system = LotterySystem(
            lotteryConfig = lotteryConfig,
            gamblingConfig = gamblingConfig,
            clock = clock,
        )
    }

    @Nested
    inner class TicketPurchase {
        @Test
        fun `buying tickets deducts gold`() {
            var gold = 500L
            val result = system.buyTickets(
                playerName = "Alice",
                sessionId = sid1,
                count = 3,
                currentGold = gold,
                inTavern = true,
                deductGold = { cost -> gold -= cost },
            )
            assertTrue(result is LotteryBuyResult.Success)
            val success = result as LotteryBuyResult.Success
            assertEquals(3, success.count)
            assertEquals(300L, success.totalCost)
            assertEquals(3, success.totalTickets)
            assertEquals(200L, gold)
        }

        @Test
        fun `insufficient gold returns error`() {
            val result = system.buyTickets(
                playerName = "Alice",
                sessionId = sid1,
                count = 1,
                currentGold = 50L,
                inTavern = true,
                deductGold = { },
            )
            assertTrue(result is LotteryBuyResult.InsufficientGold)
            val err = result as LotteryBuyResult.InsufficientGold
            assertEquals(50L, err.have)
            assertEquals(100L, err.need)
        }

        @Test
        fun `max ticket limit enforced`() {
            system.buyTickets("Alice", sid1, 5, 10000L, inTavern = true) { }
            val result = system.buyTickets("Alice", sid1, 1, 10000L, inTavern = true) { }
            assertTrue(result is LotteryBuyResult.ExceedsLimit)
            val err = result as LotteryBuyResult.ExceedsLimit
            assertEquals(5, err.current)
            assertEquals(5, err.max)
        }

        @Test
        fun `ticket purchase adds to jackpot`() {
            system.buyTickets("Alice", sid1, 1, 10000L, inTavern = true) { }
            val info = system.getInfo("Alice")
            // Base seed 500 + 80% of 100 = 580
            assertEquals(580L, info.jackpot)
        }

        @Test
        fun `disabled lottery returns Disabled`() {
            val disabledSystem = LotterySystem(
                lotteryConfig = lotteryConfig.copy(enabled = false),
                gamblingConfig = gamblingConfig,
                clock = clock,
            )
            val result = disabledSystem.buyTickets("Alice", sid1, 1, 10000L, inTavern = true) { }
            assertTrue(result is LotteryBuyResult.Disabled)
        }

        @Test
        fun `buying outside a tavern returns NotInTavern`() {
            var deducted = false
            val result = system.buyTickets("Alice", sid1, 1, 10000L, inTavern = false) { deducted = true }
            assertTrue(result is LotteryBuyResult.NotInTavern)
            assertTrue(!deducted)
            assertEquals(0, system.getInfo("Alice").playerTickets)
        }
    }

    @Nested
    inner class LotteryDrawing {
        @Test
        fun `no drawing before interval`() {
            clock.advance(30_000L) // half the interval
            val result = system.tick(clock.millis())
            assertNull(result)
        }

        @Test
        fun `drawing occurs after interval`() {
            system.buyTickets("Alice", sid1, 1, 10000L, inTavern = true) { }
            clock.advance(60_000L)
            val result = system.tick(clock.millis())
            assertNotNull(result)
            assertEquals("alice", result!!.winnerName)
        }

        @Test
        fun `no tickets results in rollover`() {
            clock.advance(60_000L)
            val result = system.tick(clock.millis())
            assertNotNull(result)
            assertNull(result!!.winnerName)
            assertEquals(500L, result.jackpotAmount)
            assertEquals(0, result.totalTickets)
        }

        @Test
        fun `jackpot resets to seed after drawing with winner`() {
            system.buyTickets("Alice", sid1, 1, 10000L, inTavern = true) { }
            clock.advance(60_000L)
            system.tick(clock.millis())
            val info = system.getInfo("Alice")
            assertEquals(500L, info.jackpot) // reset to seed
            assertEquals(0, info.playerTickets) // tickets cleared
        }

        @Test
        fun `jackpot calculation is correct`() {
            // Buy 5 tickets at 100 gold each = 500 gold total
            // 80% of 500 = 400 added to jackpot
            // Base seed 500 + 400 = 900
            system.buyTickets("Alice", sid1, 3, 10000L, inTavern = true) { }
            system.buyTickets("Bob", sid2, 2, 10000L, inTavern = true) { }
            val info = system.getInfo("Alice")
            assertEquals(900L, info.jackpot)
            assertEquals(5, info.totalTickets)
        }

        @Test
        fun `winner selected from ticket holders`() {
            val fixedRandom = Random(42)
            val fixedSystem = LotterySystem(
                lotteryConfig = lotteryConfig,
                gamblingConfig = gamblingConfig,
                clock = clock,
                random = fixedRandom,
            )
            fixedSystem.buyTickets("Alice", sid1, 3, 10000L, inTavern = true) { }
            fixedSystem.buyTickets("Bob", sid2, 2, 10000L, inTavern = true) { }

            clock.advance(60_000L)
            val result = fixedSystem.tick(clock.millis())
            assertNotNull(result)
            assertNotNull(result!!.winnerName)
            assertTrue(result.winnerName == "alice" || result.winnerName == "bob")
        }
    }

    @Nested
    inner class LotteryInfoDisplay {
        @Test
        fun `getInfo shows correct state`() {
            system.buyTickets("Alice", sid1, 2, 10000L, inTavern = true) { }
            system.buyTickets("Bob", sid2, 3, 10000L, inTavern = true) { }
            val info = system.getInfo("Alice")
            assertEquals(2, info.playerTickets)
            assertEquals(5, info.totalTickets)
            // 500 + 80% of 200 + 80% of 300 = 500 + 160 + 240 = 900
            assertEquals(900L, info.jackpot)
        }

        @Test
        fun `getInfo shows zero tickets for non-participant`() {
            system.buyTickets("Alice", sid1, 2, 10000L, inTavern = true) { }
            val info = system.getInfo("Bob")
            assertEquals(0, info.playerTickets)
            assertEquals(2, info.totalTickets)
        }
    }

    /**
     * A [Random] that hands back a scripted sequence of `nextInt` results,
     * letting each dice roll (and the coin flip) be pinned exactly. Values are
     * reduced modulo `until` so the same script works across die sizes.
     */
    private class ScriptedRandom(
        private val values: IntArray,
    ) : Random() {
        private var index = 0

        override fun nextBits(bitCount: Int): Int = 0

        override fun nextInt(until: Int): Int {
            val v = values[index % values.size]
            index++
            return ((v % until) + until) % until
        }
    }

    private fun systemWith(random: Random): LotterySystem =
        LotterySystem(
            lotteryConfig = lotteryConfig,
            gamblingConfig = gamblingConfig,
            clock = clock,
            random = random,
        )

    @Nested
    inner class DiceGambling {
        // Roll order: Ophirae(d20), Mycorae(d20), Pyrae(d16), Aetherae(d16),
        // Lustriae(d8), Aureliae(d8). A nextInt return of N yields a face of N+1.

        @Test
        fun `low sum with no coin wins the base multiplier`() {
            // All dice show 1 → sum 6, no max faces, no coin.
            val result = systemWith(ScriptedRandom(intArrayOf(0))).gamble(sid1, 100L, 1000L, inTavern = true)
            assertTrue(result is GambleResult.Resolved)
            val r = result as GambleResult.Resolved
            assertEquals(6, r.sum)
            assertEquals(6, r.dice.size)
            assertTrue(r.baseWin)
            assertTrue(!r.coinFired)
            assertEquals(2.0, r.multiplier)
            assertEquals(200L, r.payout)
            assertTrue(r.won)
        }

        @Test
        fun `high sum with no coin loses`() {
            // 15,15,12,12,2,2 → sum 58, no die at its max face.
            val result = systemWith(ScriptedRandom(intArrayOf(14, 14, 11, 11, 1, 1)))
                .gamble(sid1, 100L, 1000L, inTavern = true)
            assertTrue(result is GambleResult.Resolved)
            val r = result as GambleResult.Resolved
            assertEquals(58, r.sum)
            assertEquals(0, r.maxCount)
            assertTrue(!r.coinFired)
            assertEquals(0.0, r.multiplier)
            assertEquals(0L, r.payout)
            assertTrue(!r.won)
        }

        @Test
        fun `three max faces on a bust fire a winning coin for the coin multiplier`() {
            // 20,20,16 (three maxes), 10,4,4 → sum 74 (bust); coin flip wins (0).
            val result = systemWith(ScriptedRandom(intArrayOf(19, 19, 15, 9, 3, 3, 0)))
                .gamble(sid1, 100L, 1000L, inTavern = true)
            assertTrue(result is GambleResult.Resolved)
            val r = result as GambleResult.Resolved
            assertEquals(3, r.maxCount)
            assertTrue(r.coinFired)
            assertTrue(r.coinWon)
            assertTrue(!r.baseWin)
            assertEquals(10.0, r.multiplier)
            assertEquals(1000L, r.payout)
        }

        @Test
        fun `coin win on a held sum pays the jackpot multiplier`() {
            // 1,1,16,1,8,8 → sum 35 (held), three maxes (Pyrae/Lustriae/Aureliae); coin wins (0).
            val result = systemWith(ScriptedRandom(intArrayOf(0, 0, 15, 0, 7, 7, 0)))
                .gamble(sid1, 100L, 1000L, inTavern = true)
            assertTrue(result is GambleResult.Resolved)
            val r = result as GambleResult.Resolved
            assertEquals(35, r.sum)
            assertEquals(3, r.maxCount)
            assertTrue(r.coinFired)
            assertTrue(r.coinWon)
            assertTrue(r.baseWin)
            assertEquals(12.0, r.multiplier)
            assertEquals(1200L, r.payout)
        }

        @Test
        fun `coin fires but a losing flip keeps the base bust`() {
            // 20,20,16,10,4,4 → sum 74 (bust), three maxes; coin flip loses (1).
            val result = systemWith(ScriptedRandom(intArrayOf(19, 19, 15, 9, 3, 3, 1)))
                .gamble(sid1, 100L, 1000L, inTavern = true)
            assertTrue(result is GambleResult.Resolved)
            val r = result as GambleResult.Resolved
            assertTrue(r.coinFired)
            assertTrue(!r.coinWon)
            assertEquals(0.0, r.multiplier)
            assertEquals(0L, r.payout)
            assertTrue(!r.won)
        }

        @Test
        fun `not in tavern returns NotInTavern`() {
            val result = system.gamble(sid1, 100L, 1000L, inTavern = false)
            assertTrue(result is GambleResult.NotInTavern)
        }

        @Test
        fun `bet below minimum returns BetTooLow`() {
            val result = system.gamble(sid1, 5L, 1000L, inTavern = true)
            assertTrue(result is GambleResult.BetTooLow)
            assertEquals(10L, (result as GambleResult.BetTooLow).min)
        }

        @Test
        fun `bet above maximum returns BetTooHigh`() {
            val result = system.gamble(sid1, 5000L, 10000L, inTavern = true)
            assertTrue(result is GambleResult.BetTooHigh)
            assertEquals(1000L, (result as GambleResult.BetTooHigh).max)
        }

        @Test
        fun `insufficient gold returns InsufficientGold`() {
            val result = system.gamble(sid1, 100L, 50L, inTavern = true)
            assertTrue(result is GambleResult.InsufficientGold)
            val err = result as GambleResult.InsufficientGold
            assertEquals(50L, err.have)
            assertEquals(100L, err.need)
        }

        @Test
        fun `cooldown enforced between gambles`() {
            system.gamble(sid1, 10L, 1000L, inTavern = true)
            val result = system.gamble(sid1, 10L, 1000L, inTavern = true)
            assertTrue(result is GambleResult.OnCooldown)
        }

        @Test
        fun `cooldown expires after configured interval`() {
            system.gamble(sid1, 10L, 1000L, inTavern = true)
            clock.advance(5_000L)
            val result = system.gamble(sid1, 10L, 1000L, inTavern = true)
            assertTrue(result is GambleResult.Resolved)
        }

        @Test
        fun `disabled gambling returns Disabled`() {
            val disabledSystem = LotterySystem(
                lotteryConfig = lotteryConfig,
                gamblingConfig = gamblingConfig.copy(enabled = false),
                clock = clock,
            )
            val result = disabledSystem.gamble(sid1, 100L, 1000L, inTavern = true)
            assertTrue(result is GambleResult.Disabled)
        }

        @Test
        fun `cooldown cleared on disconnect`() {
            system.gamble(sid1, 10L, 1000L, inTavern = true)
            system.onDisconnect(sid1)
            val result = system.gamble(sid1, 10L, 1000L, inTavern = true)
            assertTrue(result is GambleResult.Resolved)
        }
    }

    @Nested
    inner class Persistence {
        @Test
        fun `persist and load round-trips state`(
            @TempDir tempDir: Path,
        ) {
            val path = tempDir.resolve("lottery_state.json")
            val sys1 = LotterySystem(
                lotteryConfig = lotteryConfig,
                gamblingConfig = gamblingConfig,
                clock = clock,
                persistPath = path,
            )
            sys1.buyTickets("Alice", sid1, 3, 10000L, inTavern = true) { }
            sys1.buyTickets("Bob", sid2, 2, 10000L, inTavern = true) { }
            assertTrue(path.exists())

            val sys2 = LotterySystem(
                lotteryConfig = lotteryConfig,
                gamblingConfig = gamblingConfig,
                clock = clock,
                persistPath = path,
            )
            sys2.loadPersistedState()

            val info = sys2.getInfo("Alice")
            assertEquals(3, info.playerTickets)
            assertEquals(5, info.totalTickets)
            assertEquals(900L, info.jackpot)
        }
    }
}
