package dev.ambon.engine.status

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.TEST_ROOM_ID
import dev.ambon.test.TEST_SESSION_ID
import dev.ambon.test.TestFixtureBase
import dev.ambon.test.buildTestPlayerRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Proves DOT/HOT tick values scale with caster level + relevant stat using
 * the same formula as direct spell/heal damage, and that omitting the caster
 * context falls back to the authored range (backward compatibility).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusEffectScalingTest {
    private val sid = TEST_SESSION_ID
    private val mobId = MobId("zone:goblin")

    /** Variance fixed at 1.0 so tick values are fully deterministic. */
    private val bindings =
        StatBindingsConfig(
            spellDamageStat = "INT",
            spellStatMultiplier = 1.0,
            spellLevelScalingRate = 1.30,
            spellVarianceMin = 1.0,
            spellVarianceMax = 1.0,
            healStat = "WIS",
            healStatMultiplier = 1.0,
            healLevelScalingRate = 1.30,
            healVarianceMin = 1.0,
            healVarianceMax = 1.0,
        )

    private inner class Fixture : TestFixtureBase {
        val clock = MutableClock(0L)
        val items = ItemRegistry()
        val repo = InMemoryPlayerRepository()
        override val roomId: RoomId = TEST_ROOM_ID
        override val players: PlayerRegistry = buildTestPlayerRegistry(roomId, repo, items, clock = clock)
        override val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val registry = StatusEffectRegistry()
        val system =
            StatusEffectSystem(
                registry = registry,
                players = players,
                mobs = mobs,
                outbound = outbound,
                clock = clock,
                rng = Random(42),
                bindings = bindings,
            )

        fun login() {
            runBlocking { players.login(sid, "Caster", "pass", defaultAnsiEnabled = false) }
        }

        fun registerDot(min: Int = 5, max: Int = 5) {
            registry.register(
                StatusEffectDefinition(
                    id = StatusEffectId("ignite"),
                    displayName = "Ignite",
                    effectType = "dot",
                    durationMs = 6000,
                    tickIntervalMs = 2000,
                    tickMinValue = min,
                    tickMaxValue = max,
                ),
            )
        }

        fun registerHot(min: Int = 3, max: Int = 3) {
            registry.register(
                StatusEffectDefinition(
                    id = StatusEffectId("rejuv"),
                    displayName = "Rejuvenation",
                    effectType = "hot",
                    durationMs = 6000,
                    tickIntervalMs = 2000,
                    tickMinValue = min,
                    tickMaxValue = max,
                ),
            )
        }
    }

    @Test
    fun `DOT tick scales with caster level`() =
        runTest {
            val h = Fixture()
            h.login()
            val mob = h.spawnMob(id = mobId, name = "Dummy", hp = 5000)
            h.registerDot(min = 10, max = 10)

            // Level 10 caster, no stat bonus (casterStats omitted, like a mob caster)
            // → anchor = 10 × 1.30^9 ≈ 106
            val expected = (10.0 * 1.30.pow(9)).roundToInt()
            h.system.applyToMob(
                mobId = mobId,
                effectId = StatusEffectId("ignite"),
                casterLevel = 10,
            )
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())

            assertEquals(5000 - expected, mob.hp, "DOT tick should scale with level")
        }

    @Test
    fun `DOT tick scales with caster spell-damage stat`() =
        runTest {
            val h = Fixture()
            h.login()
            val mob = h.spawnMob(id = mobId, name = "Dummy", hp = 5000)
            h.registerDot(min = 10, max = 10)

            // Level 1, INT = BASE + 20 → anchor = (10 + 20×1.0) × 1.0 = 30
            val intBonus = 20
            val expected = (10.0 + intBonus * 1.0).roundToInt()
            h.system.applyToMob(
                mobId = mobId,
                effectId = StatusEffectId("ignite"),
                casterLevel = 1,
                casterStats = StatMap.of("INT" to PlayerState.BASE_STAT + intBonus),
            )
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())

            assertEquals(5000 - expected, mob.hp, "DOT tick should scale with INT")
        }

    @Test
    fun `HOT tick scales with caster level and WIS`() =
        runTest {
            val h = Fixture()
            h.login()
            val player = h.players.get(sid)!!
            player.maxHp = 100_000
            player.hp = 1
            h.registerHot(min = 4, max = 4)

            // Level 5, WIS = BASE + 6 → anchor = (4 + 6×1.0) × 1.30^4 ≈ 28.561
            val wisBonus = 6
            val expected = ((4.0 + wisBonus * 1.0) * 1.30.pow(4)).roundToInt()
            h.system.applyToPlayer(
                sessionId = sid,
                effectId = StatusEffectId("rejuv"),
                casterLevel = 5,
                casterStats = StatMap.of("WIS" to PlayerState.BASE_STAT + wisBonus),
            )
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())

            assertEquals(1 + expected, player.hp, "HOT tick should scale with level + WIS")
        }

    @Test
    fun `omitting caster context falls back to authored range`() =
        runTest {
            val h = Fixture()
            h.login()
            val mob = h.spawnMob(id = mobId, name = "Dummy", hp = 100)
            h.registerDot(min = 5, max = 5)

            // No casterLevel passed → tickAnchor is null → uses authored rollRange(5, 5) = 5
            h.system.applyToMob(mobId = mobId, effectId = StatusEffectId("ignite"))
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())

            assertEquals(95, mob.hp, "Without caster context, DOT should use authored range")
        }

    @Test
    fun `snapshot is taken at apply time and does not change mid-duration`() =
        runTest {
            val h = Fixture()
            h.login()
            val mob = h.spawnMob(id = mobId, name = "Dummy", hp = 100_000)
            h.registerDot(min = 10, max = 10)

            h.system.applyToMob(
                mobId = mobId,
                effectId = StatusEffectId("ignite"),
                casterLevel = 1,
            )
            // Tick at 2s — level 1, anchor 10
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())
            // Tick at 4s — same snapshot, still 10
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())

            assertTrue(mob.hp == 100_000 - 20, "Both ticks should use level-1 snapshot (10 each)")
        }
}
