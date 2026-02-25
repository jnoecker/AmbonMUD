package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class RegenSystemTest {
    private val roomId = RoomId("zone:room")

    private fun makeRegen(
        players: PlayerRegistry,
        clock: MutableClock,
        baseIntervalMs: Long = 5_000L,
        manaBaseIntervalMs: Long = 3_000L,
    ): RegenSystem =
        RegenSystem(
            players = players,
            items = ItemRegistry(),
            clock = clock,
            rng = Random(42),
            baseIntervalMs = baseIntervalMs,
            manaBaseIntervalMs = manaBaseIntervalMs,
        )

    private fun makeRegistry(): PlayerRegistry =
        PlayerRegistry(roomId, InMemoryPlayerRepository(), ItemRegistry())

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
            regen.tick(maxPlayersPerTick = 2)

            val healed = listOf(sid1, sid2, sid3).count { players.get(it)!!.hp > 1 }
            assertTrue(healed <= 2, "Expected at most 2 players healed, got $healed")
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
