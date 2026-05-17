package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.TEST_ROOM_ID
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class RegenSystemTest {
    private val roomId = TEST_ROOM_ID

    private fun makeRegen(
        players: PlayerRegistry,
        clock: MutableClock,
        baseIntervalMs: Long = 5_000L,
        manaBaseIntervalMs: Long = 3_000L,
        hpRegenPercent: Double = 0.10,
        manaRegenPercent: Double = 0.05,
        inCombatMultiplier: Double = 0.5,
        inCombat: (dev.ambon.domain.ids.SessionId) -> Boolean = { false },
    ): RegenSystem =
        RegenSystem(
            players = players,
            items = ItemRegistry(),
            clock = clock,
            rng = Random(42),
            baseIntervalMs = baseIntervalMs,
            manaBaseIntervalMs = manaBaseIntervalMs,
            hpRegenPercent = hpRegenPercent,
            manaRegenPercent = manaRegenPercent,
            inCombatMultiplier = inCombatMultiplier,
            inCombat = inCombat,
        )

    private fun makeRegistry(): PlayerRegistry =
        dev.ambon.test.buildTestPlayerRegistry(roomId, InMemoryPlayerRepository(), ItemRegistry())

    @Test
    fun `tick with no players does not crash`() =
        runTest {
            val players = makeRegistry()
            val regen = makeRegen(players, MutableClock(0L))
            regen.tick() // should complete without exception
        }

    @Test
    fun `players regen hp after interval`() =
        runTest {
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val regen = makeRegen(players, clock)

            val sid = SessionId(1L)
            players.loginOrFail(sid, "Alice")

            // Tick at t=0 while HP is full — records lastRegenAtMs
            regen.tick()

            val player = players.get(sid)!!
            player.hp = player.maxHp - 3

            // Advance past the default 5000ms regen interval
            clock.advance(5_000L)
            regen.tick()

            assertEquals(player.maxHp - 2, player.hp, "Expected one regen tick (+1 HP)")
        }

    @Test
    fun `players regen mana after interval`() =
        runTest {
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val regen = makeRegen(players, clock)

            val sid = SessionId(1L)
            players.loginOrFail(sid, "Bob")

            // Tick at t=0 while mana is full — records lastManaRegenAtMs
            regen.tick()

            val player = players.get(sid)!!
            player.mana = player.maxMana - 5

            // Advance past the default 3000ms mana regen interval
            clock.advance(3_000L)
            regen.tick()

            assertEquals(player.maxMana - 4, player.mana, "Expected one mana regen tick (+1 mana)")
        }

    @Test
    fun `players with depleted mana at login still regen after interval`() =
        runTest {
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val regen = makeRegen(players, clock)

            val sid = SessionId(1L)
            players.loginOrFail(sid, "Eve")

            val player = players.get(sid)!!
            player.mana = player.maxMana - 5

            regen.tick()
            clock.advance(3_000L)
            regen.tick()

            assertEquals(player.maxMana - 4, player.mana, "Expected one mana regen tick (+1 mana)")
        }

    @Test
    fun `players at full hp do not regen past max`() =
        runTest {
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val regen = makeRegen(players, clock)

            val sid = SessionId(1L)
            players.loginOrFail(sid, "Carol")

            val player = players.get(sid)!!
            val originalHp = player.hp

            clock.advance(10_000L)
            regen.tick()

            assertEquals(originalHp, player.hp, "Full HP player should not regen beyond max")
        }

    @Test
    fun `maxPlayersPerTick limits how many players are healed per tick`() =
        runTest {
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val regen = makeRegen(players, clock, baseIntervalMs = 100L, manaBaseIntervalMs = 100L)

            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            val sid3 = SessionId(3L)
            players.loginOrFail(sid1, "Player1")
            players.loginOrFail(sid2, "Player2")
            players.loginOrFail(sid3, "Player3")

            // Tick while all are at full HP — seed all regen timers
            regen.tick()

            // Damage all three players
            players.get(sid1)!!.hp = 1
            players.get(sid2)!!.hp = 1
            players.get(sid3)!!.hp = 1

            // Advance past regen interval
            clock.advance(200L)

            // Limit to 2 players per tick
            regen.tick(capOverride = 2)

            val healed = listOf(sid1, sid2, sid3).count { players.get(it)!!.hp > 1 }
            assertTrue(healed <= 2, "Expected at most 2 players healed, got $healed")
        }

    @Test
    fun `maxPlayersPerTick counts every inspected player not just healed players`() =
        runTest {
            // With the old (buggy) implementation ran only incremented when a player actually
            // received regen.  Full-HP players were free to inspect without consuming the budget,
            // so all N players were visited every tick regardless of the cap.
            //
            // With the fix, ran++ happens for every player visited.  We verify this by placing
            // 10 players all at full HP with a cap of 10 (exactly equal), then confirming a
            // damaged 11th player — sitting just outside the cap — is NOT healed when the 10
            // full-HP players fill the budget first.
            //
            // Random(42).nextInt(11) == 1, so iteration visits players at indices
            // 1,2,3,4,5,6,7,8,9,10 — the damaged player is at index 0 (sid1) and is not
            // reached, confirming the budget was consumed by the full-HP players.
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val regen = makeRegen(players, clock, baseIntervalMs = 100L, manaBaseIntervalMs = 100L)

            val sids = (1..11).map { SessionId(it.toLong()) }
            sids.forEachIndexed { idx, sid -> players.loginOrFail(sid, "Player$idx") }

            // Seed all timers at t=0
            regen.tick()

            // Damage only sid1 (index 0 in the list)
            val damagedPlayer = players.get(sids[0])!!
            val damagedHp = damagedPlayer.maxHp - 1
            damagedPlayer.hp = damagedHp

            clock.advance(200L)

            // Cap = 10, 11 players total.  The 10 full-HP players consume the cap; sid1 (index 0)
            // is not reached when iteration starts at index 1 (Random(42).nextInt(11) == 1).
            regen.tick(capOverride = 10)

            assertEquals(
                damagedHp,
                damagedPlayer.hp,
                "Damaged player must not be healed when cap is consumed by full-HP players",
            )
        }

    @Test
    fun `adaptiveCap scales with player count to hit the cycle target`() {
        val regen =
            RegenSystem(
                players = makeRegistry(),
                items = ItemRegistry(),
                clock = MutableClock(0L),
                tickIntervalMs = 100L,
                cycleTargetMs = 2_000L,
                minPlayersPerTick = 5,
                maxPlayersPerTick = 200,
            )
        // 100 players * 100ms / 2000ms = 5/tick → cycles in 2s
        assertEquals(5, regen.adaptiveCap(100))
        // 500 * 100 / 2000 = 25
        assertEquals(25, regen.adaptiveCap(500))
        // 1437 * 100 / 2000 = 71.85 → ceil = 72
        assertEquals(72, regen.adaptiveCap(1437))
    }

    @Test
    fun `adaptiveCap respects floor at low populations`() {
        val regen =
            RegenSystem(
                players = makeRegistry(),
                items = ItemRegistry(),
                clock = MutableClock(0L),
                tickIntervalMs = 100L,
                cycleTargetMs = 2_000L,
                minPlayersPerTick = 5,
                maxPlayersPerTick = 200,
            )
        // Raw would be 10 * 100 / 2000 = 0.5 → ceil = 1 → floor lifts to 5
        assertEquals(5, regen.adaptiveCap(10))
        // Raw = 1 → floor = 5
        assertEquals(5, regen.adaptiveCap(1))
    }

    @Test
    fun `adaptiveCap respects ceiling at very high populations`() {
        val regen =
            RegenSystem(
                players = makeRegistry(),
                items = ItemRegistry(),
                clock = MutableClock(0L),
                tickIntervalMs = 100L,
                cycleTargetMs = 2_000L,
                minPlayersPerTick = 5,
                maxPlayersPerTick = 200,
            )
        // 10000 * 100 / 2000 = 500 → ceiling 200 → cycle degrades to 5s, engine protected
        assertEquals(200, regen.adaptiveCap(10_000))
    }

    @Test
    fun `adaptiveCap returns zero for empty population`() {
        val regen =
            RegenSystem(
                players = makeRegistry(),
                items = ItemRegistry(),
                clock = MutableClock(0L),
            )
        assertEquals(0, regen.adaptiveCap(0))
    }

    @Test
    fun `tick without override uses adaptive cap`() =
        runTest {
            val players = makeRegistry()
            val regen =
                RegenSystem(
                    players = players,
                    items = ItemRegistry(),
                    clock = MutableClock(0L),
                    rng = Random(42),
                    // Zero intervals → any visited, damaged player heals immediately on first tick.
                    baseIntervalMs = 0L,
                    minIntervalMs = 0L,
                    manaBaseIntervalMs = 0L,
                    manaMinIntervalMs = 0L,
                    // 10% of maxHp (10) = 1 HP per regen.
                    hpRegenPercent = 0.10,
                    manaRegenPercent = 0.10,
                    // 5 players * 100ms / 1000ms = 0.5 → ceil 1 → floor lifts to 1
                    tickIntervalMs = 100L,
                    cycleTargetMs = 1_000L,
                    minPlayersPerTick = 1,
                    maxPlayersPerTick = 200,
                )

            val sids = (1..5).map { SessionId(it.toLong()) }
            sids.forEachIndexed { idx, sid -> players.loginOrFail(sid, "Player$idx") }
            sids.forEach { players.get(it)!!.hp = 1 }

            regen.tick() // adaptive cap = 1

            val healed = sids.count { players.get(it)!!.hp > 1 }
            assertEquals(1, healed, "Expected adaptive cap of 1 to heal exactly one player")
        }

    @Test
    fun `regen scales with max pool size`() =
        runTest {
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val regen = makeRegen(players, clock, hpRegenPercent = 0.25)

            val sid = SessionId(1L)
            players.loginOrFail(sid, "Sized")

            val player = players.get(sid)!!
            // Boost the pool so percent-based regen is non-trivial.
            player.maxHp = 100
            player.hp = 1

            regen.tick() // seed
            clock.advance(5_000L)
            regen.tick()

            // 25% of 100 = 25 HP per tick out of combat.
            assertEquals(26, player.hp, "Expected +25 HP regen at 25% of 100 maxHp")
        }

    @Test
    fun `small pools still regen at least 1 per tick`() =
        runTest {
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val regen = makeRegen(players, clock, manaRegenPercent = 0.05)

            val sid = SessionId(1L)
            players.loginOrFail(sid, "Tiny")

            val player = players.get(sid)!!
            // Starting mana pool is small; 5% of 10 floors to 0, which would
            // strand new characters with no mana regen.
            player.maxMana = 10
            player.mana = 1

            regen.tick() // seed
            clock.advance(3_000L)
            regen.tick()

            assertEquals(2, player.mana, "Small pools must still regen at least 1 per tick")
        }

    @Test
    fun `in-combat players regen at reduced rate`() =
        runTest {
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val sid = SessionId(1L)
            val regen =
                makeRegen(
                    players,
                    clock,
                    hpRegenPercent = 0.50,
                    inCombatMultiplier = 0.5,
                    inCombat = { it == sid },
                )

            players.loginOrFail(sid, "Brawler")

            val player = players.get(sid)!!
            player.maxHp = 100
            player.hp = 1

            regen.tick() // seed
            clock.advance(5_000L)
            regen.tick()

            // 50% of 100 = 50, halved by in-combat multiplier = 25 HP.
            assertEquals(26, player.hp, "Expected +25 HP (50% of maxHp × 0.5 in-combat multiplier)")
        }

    @Test
    fun `in-combat multiplier of zero disables regen during fights`() =
        runTest {
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val sid = SessionId(1L)
            val regen =
                makeRegen(
                    players,
                    clock,
                    hpRegenPercent = 0.50,
                    inCombatMultiplier = 0.0,
                    inCombat = { it == sid },
                )

            players.loginOrFail(sid, "Locked")

            val player = players.get(sid)!!
            player.maxHp = 100
            player.hp = 1

            regen.tick() // seed
            clock.advance(5_000L)
            regen.tick()

            assertEquals(1, player.hp, "Player in combat with multiplier=0 should not regen")
        }

    @Test
    fun `out-of-combat players ignore in-combat multiplier`() =
        runTest {
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val sid = SessionId(1L)
            val regen =
                makeRegen(
                    players,
                    clock,
                    hpRegenPercent = 0.50,
                    inCombatMultiplier = 0.0,
                    inCombat = { false },
                )

            players.loginOrFail(sid, "Safe")

            val player = players.get(sid)!!
            player.maxHp = 100
            player.hp = 1

            regen.tick() // seed
            clock.advance(5_000L)
            regen.tick()

            // Out of combat → full 50% of 100 = 50 HP regen.
            assertEquals(51, player.hp, "Out-of-combat player should regen at full percent")
        }

    @Test
    fun `regen does not fire before interval elapses`() =
        runTest {
            val players = makeRegistry()
            val clock = MutableClock(0L)
            val regen = makeRegen(players, clock, baseIntervalMs = 5_000L)

            val sid = SessionId(1L)
            players.loginOrFail(sid, "Dave")

            regen.tick() // seed timer at t=0

            val player = players.get(sid)!!
            player.hp = player.maxHp - 2

            // Advance to just under the interval
            clock.advance(4_999L)
            regen.tick()

            assertEquals(player.maxHp - 2, player.hp, "HP should not regen before interval elapses")
        }
}
