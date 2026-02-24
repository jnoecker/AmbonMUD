package dev.ambon.engine.status

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class StatusEffectSystemTest {
    private val roomId = RoomId("zone:room")
    private val sid = SessionId(1L)
    private val mobId = MobId("zone:goblin")

    private fun buildSystem(
        clock: MutableClock = MutableClock(0L),
        rng: Random = Random(42),
    ): TestHarness {
        val items = ItemRegistry()
        val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val registry = StatusEffectRegistry()
        val vitalsDirty = mutableListOf<SessionId>()
        val mobHpDirty = mutableListOf<MobId>()
        val statusDirty = mutableListOf<SessionId>()

        val system =
            StatusEffectSystem(
                registry = registry,
                players = players,
                mobs = mobs,
                outbound = outbound,
                clock = clock,
                rng = rng,
                markVitalsDirty = { vitalsDirty.add(it) },
                markMobHpDirty = { mobHpDirty.add(it) },
                markStatusDirty = { statusDirty.add(it) },
            )

        return TestHarness(
            players = players,
            mobs = mobs,
            outbound = outbound,
            registry = registry,
            system = system,
            clock = clock,
            vitalsDirty = vitalsDirty,
            mobHpDirty = mobHpDirty,
            statusDirty = statusDirty,
        )
    }

    private data class TestHarness(
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val outbound: LocalOutboundBus,
        val registry: StatusEffectRegistry,
        val system: StatusEffectSystem,
        val clock: MutableClock,
        val vitalsDirty: MutableList<SessionId>,
        val mobHpDirty: MutableList<MobId>,
        val statusDirty: MutableList<SessionId>,
    )

    private fun TestHarness.loginPlayer() {
        val result =
            kotlinx.coroutines.runBlocking {
                players.login(sid, "TestPlayer", "pass", defaultAnsiEnabled = false)
            }
        assertEquals(LoginResult.Ok, result)
    }

    private fun TestHarness.spawnMob(
        hp: Int = 50,
        id: MobId = mobId,
    ): MobState {
        val mob =
            MobState(
                id = id,
                name = "Goblin",
                roomId = roomId,
                hp = hp,
                maxHp = hp,
                minDamage = 1,
                maxDamage = 2,
            )
        mobs.upsert(mob)
        return mob
    }

    private fun TestHarness.registerDot(
        id: String = "ignite",
        durationMs: Long = 6000,
        tickIntervalMs: Long = 2000,
        minVal: Int = 5,
        maxVal: Int = 5,
        stackBehavior: StackBehavior = StackBehavior.REFRESH,
        maxStacks: Int = 1,
    ) {
        registry.register(
            StatusEffectDefinition(
                id = StatusEffectId(id),
                displayName = "Ignite",
                effectType = EffectType.DOT,
                durationMs = durationMs,
                tickIntervalMs = tickIntervalMs,
                tickMinValue = minVal,
                tickMaxValue = maxVal,
                stackBehavior = stackBehavior,
                maxStacks = maxStacks,
            ),
        )
    }

    private fun TestHarness.registerHot(
        id: String = "rejuvenation",
        durationMs: Long = 6000,
        tickIntervalMs: Long = 2000,
        minVal: Int = 3,
        maxVal: Int = 3,
    ) {
        registry.register(
            StatusEffectDefinition(
                id = StatusEffectId(id),
                displayName = "Rejuvenation",
                effectType = EffectType.HOT,
                durationMs = durationMs,
                tickIntervalMs = tickIntervalMs,
                tickMinValue = minVal,
                tickMaxValue = maxVal,
            ),
        )
    }

    private fun TestHarness.registerShield(
        id: String = "shield",
        durationMs: Long = 30000,
        shieldAmount: Int = 20,
    ) {
        registry.register(
            StatusEffectDefinition(
                id = StatusEffectId(id),
                displayName = "Shield of Faith",
                effectType = EffectType.SHIELD,
                durationMs = durationMs,
                shieldAmount = shieldAmount,
                stackBehavior = StackBehavior.NONE,
            ),
        )
    }

    private fun TestHarness.registerStatBuff(
        id: String = "battle_shout",
        durationMs: Long = 60000,
        str: Int = 3,
    ) {
        registry.register(
            StatusEffectDefinition(
                id = StatusEffectId(id),
                displayName = "Battle Shout",
                effectType = EffectType.STAT_BUFF,
                durationMs = durationMs,
                statMods = StatModifiers(str = str),
            ),
        )
    }

    private fun TestHarness.registerStun(
        id: String = "concuss",
        durationMs: Long = 2000,
    ) {
        registry.register(
            StatusEffectDefinition(
                id = StatusEffectId(id),
                displayName = "Concuss",
                effectType = EffectType.STUN,
                durationMs = durationMs,
                stackBehavior = StackBehavior.NONE,
            ),
        )
    }

    private fun TestHarness.registerRoot(
        id: String = "frost_grip",
        durationMs: Long = 4000,
    ) {
        registry.register(
            StatusEffectDefinition(
                id = StatusEffectId(id),
                displayName = "Frost Grip",
                effectType = EffectType.ROOT,
                durationMs = durationMs,
                stackBehavior = StackBehavior.NONE,
            ),
        )
    }

    // ── DOT Tests ──

    @Test
    fun `DOT ticks damage on player at correct intervals`() =
        runTest {
            val h = buildSystem()
            h.loginPlayer()
            h.registerDot(durationMs = 6000, tickIntervalMs = 2000, minVal = 5, maxVal = 5)
            val player = h.players.get(sid)!!
            val initialHp = player.hp

            h.system.applyToPlayer(sid, StatusEffectId("ignite"))
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())

            assertTrue(player.hp < initialHp, "DOT should have damaged player")
            assertEquals(initialHp - 5, player.hp)
        }

    @Test
    fun `DOT ticks damage on mob at correct intervals`() =
        runTest {
            val h = buildSystem()
            h.loginPlayer()
            val mob = h.spawnMob(hp = 50)
            h.registerDot(durationMs = 6000, tickIntervalMs = 2000, minVal = 5, maxVal = 5)

            h.system.applyToMob(mobId, StatusEffectId("ignite"), sid)
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())

            assertEquals(45, mob.hp)
            assertTrue(h.mobHpDirty.contains(mobId))
        }

    @Test
    fun `DOT expires after duration`() =
        runTest {
            val h = buildSystem()
            h.loginPlayer()
            h.registerDot(durationMs = 4000, tickIntervalMs = 2000, minVal = 5, maxVal = 5)
            val player = h.players.get(sid)!!

            h.system.applyToPlayer(sid, StatusEffectId("ignite"))

            // Tick at 2s -> tick fires
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())
            val hpAfterFirstTick = player.hp

            // Tick at 4s -> effect should expire, no more damage
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())

            // Should have expired — no further ticks
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())
            assertEquals(hpAfterFirstTick, player.hp, "No more damage after expiry")
        }

    // ── HOT Tests ──

    @Test
    fun `HOT heals player each tick interval`() =
        runTest {
            val h = buildSystem()
            h.loginPlayer()
            h.registerHot(durationMs = 6000, tickIntervalMs = 2000, minVal = 3, maxVal = 3)
            val player = h.players.get(sid)!!
            player.hp = 5 // wound the player

            h.system.applyToPlayer(sid, StatusEffectId("rejuvenation"))
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())

            assertEquals(8, player.hp)
        }

    @Test
    fun `HOT does not heal above maxHp`() =
        runTest {
            val h = buildSystem()
            h.loginPlayer()
            h.registerHot(durationMs = 6000, tickIntervalMs = 2000, minVal = 100, maxVal = 100)
            val player = h.players.get(sid)!!
            val maxHp = player.maxHp
            player.hp = maxHp - 1

            h.system.applyToPlayer(sid, StatusEffectId("rejuvenation"))
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())

            assertEquals(maxHp, player.hp)
        }

    // ── SHIELD Tests ──

    @Test
    fun `SHIELD absorbs damage`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerShield(shieldAmount = 20)

        h.system.applyToPlayer(sid, StatusEffectId("shield"))
        val remaining = h.system.absorbPlayerDamage(sid, 15)

        assertEquals(0, remaining, "Shield should absorb all 15 damage")
    }

    @Test
    fun `SHIELD partial absorption when damage exceeds shield`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerShield(shieldAmount = 10)

        h.system.applyToPlayer(sid, StatusEffectId("shield"))
        val remaining = h.system.absorbPlayerDamage(sid, 25)

        assertEquals(15, remaining, "Should pass through 15 damage after 10 absorbed")
    }

    @Test
    fun `depleted SHIELD is removed on tick`() =
        runTest {
            val h = buildSystem()
            h.loginPlayer()
            h.registerShield(shieldAmount = 5, durationMs = 30000)

            h.system.applyToPlayer(sid, StatusEffectId("shield"))
            h.system.absorbPlayerDamage(sid, 10) // deplete the shield

            h.clock.advance(100)
            h.system.tick(h.clock.millis())

            assertFalse(h.system.hasPlayerEffect(sid, EffectType.SHIELD))
        }

    // ── STAT_BUFF Tests ──

    @Test
    fun `STAT_BUFF returns correct stat modifiers`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerStatBuff(str = 5)

        h.system.applyToPlayer(sid, StatusEffectId("battle_shout"))

        val mods = h.system.getPlayerStatMods(sid)
        assertEquals(5, mods.str)
        assertEquals(0, mods.dex)
    }

    // ── STUN Tests ──

    @Test
    fun `STUN hasPlayerEffect returns true`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerStun(durationMs = 2000)

        h.system.applyToPlayer(sid, StatusEffectId("concuss"))

        assertTrue(h.system.hasPlayerEffect(sid, EffectType.STUN))
    }

    @Test
    fun `STUN expires after duration`() =
        runTest {
            val h = buildSystem()
            h.loginPlayer()
            h.registerStun(durationMs = 2000)

            h.system.applyToPlayer(sid, StatusEffectId("concuss"))
            h.clock.advance(2100)
            h.system.tick(h.clock.millis())

            assertFalse(h.system.hasPlayerEffect(sid, EffectType.STUN))
        }

    // ── ROOT Tests ──

    @Test
    fun `ROOT hasMobEffect returns true`() {
        val h = buildSystem()
        h.spawnMob()
        h.registerRoot(durationMs = 4000)

        h.system.applyToMob(mobId, StatusEffectId("frost_grip"))

        assertTrue(h.system.hasMobEffect(mobId, EffectType.ROOT))
    }

    @Test
    fun `ROOT on player is detected`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerRoot(durationMs = 4000)

        h.system.applyToPlayer(sid, StatusEffectId("frost_grip"))

        assertTrue(h.system.hasPlayerEffect(sid, EffectType.ROOT))
    }

    // ── Stacking Behavior Tests ──

    @Test
    fun `REFRESH resets duration on reapply`() =
        runTest {
            val h = buildSystem()
            h.loginPlayer()
            h.registerDot(
                durationMs = 4000,
                tickIntervalMs = 2000,
                minVal = 5,
                maxVal = 5,
                stackBehavior = StackBehavior.REFRESH,
            )

            h.system.applyToPlayer(sid, StatusEffectId("ignite"))
            h.clock.advance(3000)
            // Reapply — should refresh duration
            h.system.applyToPlayer(sid, StatusEffectId("ignite"))

            // At 3s + 1.1s = 4.1s from original, the refreshed duration (3s + 4s = 7s) should still be active
            h.clock.advance(1100)
            h.system.tick(h.clock.millis())

            val effects = h.system.activePlayerEffects(sid)
            assertTrue(effects.isNotEmpty(), "Effect should still be active after refresh")
        }

    @Test
    fun `STACK adds independent stacks up to max`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerDot(
            id = "poison",
            durationMs = 10000,
            tickIntervalMs = 2000,
            minVal = 2,
            maxVal = 2,
            stackBehavior = StackBehavior.STACK,
            maxStacks = 3,
        )

        h.system.applyToPlayer(sid, StatusEffectId("poison"))
        h.system.applyToPlayer(sid, StatusEffectId("poison"))
        h.system.applyToPlayer(sid, StatusEffectId("poison"))

        val effects = h.system.activePlayerEffects(sid)
        val poison = effects.find { it.id == "poison" }
        assertFalse(poison == null)
        assertEquals(3, poison!!.stacks)
    }

    @Test
    fun `STACK does not exceed maxStacks`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerDot(
            id = "poison",
            durationMs = 10000,
            tickIntervalMs = 2000,
            minVal = 2,
            maxVal = 2,
            stackBehavior = StackBehavior.STACK,
            maxStacks = 2,
        )

        h.system.applyToPlayer(sid, StatusEffectId("poison"))
        h.system.applyToPlayer(sid, StatusEffectId("poison"))
        h.system.applyToPlayer(sid, StatusEffectId("poison")) // should not add a 3rd

        val effects = h.system.activePlayerEffects(sid)
        val poison = effects.find { it.id == "poison" }
        assertEquals(2, poison!!.stacks)
    }

    @Test
    fun `NONE prevents reapplication`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerStun(durationMs = 5000)

        val first = h.system.applyToPlayer(sid, StatusEffectId("concuss"))
        val second = h.system.applyToPlayer(sid, StatusEffectId("concuss"))

        assertTrue(first)
        assertFalse(second, "NONE should prevent reapplication")
    }

    // ── Cleanup Tests ──

    @Test
    fun `onPlayerDisconnected clears effects`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerStun(durationMs = 5000)
        h.system.applyToPlayer(sid, StatusEffectId("concuss"))

        h.system.onPlayerDisconnected(sid)

        assertFalse(h.system.hasPlayerEffect(sid, EffectType.STUN))
    }

    @Test
    fun `onMobRemoved clears effects`() {
        val h = buildSystem()
        h.spawnMob()
        h.registerRoot(durationMs = 5000)
        h.system.applyToMob(mobId, StatusEffectId("frost_grip"))

        h.system.onMobRemoved(mobId)

        assertFalse(h.system.hasMobEffect(mobId, EffectType.ROOT))
    }

    @Test
    fun `remapSession transfers effects`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerStatBuff(str = 3)
        h.system.applyToPlayer(sid, StatusEffectId("battle_shout"))

        val newSid = SessionId(99L)
        h.system.remapSession(sid, newSid)

        val oldMods = h.system.getPlayerStatMods(sid)
        val newMods = h.system.getPlayerStatMods(newSid)
        assertEquals(0, oldMods.str)
        assertEquals(3, newMods.str)
    }

    @Test
    fun `removeAllFromPlayer clears all effects`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerStun(durationMs = 5000)
        h.registerStatBuff(str = 3)
        h.system.applyToPlayer(sid, StatusEffectId("concuss"))
        h.system.applyToPlayer(sid, StatusEffectId("battle_shout"))

        h.system.removeAllFromPlayer(sid)

        assertFalse(h.system.hasPlayerEffect(sid, EffectType.STUN))
        assertEquals(0, h.system.getPlayerStatMods(sid).str)
    }

    // ── Mob DOT Kill Detection ──

    @Test
    fun `mobsKilledByDot detects dead mobs`() =
        runTest {
            val h = buildSystem()
            h.loginPlayer()
            val mob = h.spawnMob(hp = 3)
            h.registerDot(durationMs = 6000, tickIntervalMs = 2000, minVal = 5, maxVal = 5)

            h.system.applyToMob(mobId, StatusEffectId("ignite"), sid)
            h.clock.advance(2000)
            h.system.tick(h.clock.millis())

            val killed = h.system.mobsKilledByDot()
            assertTrue(killed.isNotEmpty())
            assertEquals(mobId, killed.first().first)
            assertEquals(sid, killed.first().second)
        }

    // ── Active Effects Snapshot ──

    @Test
    fun `activePlayerEffects returns correct snapshot`() {
        val h = buildSystem()
        h.loginPlayer()
        h.registerStatBuff(str = 3, durationMs = 10000)
        h.system.applyToPlayer(sid, StatusEffectId("battle_shout"))

        val effects = h.system.activePlayerEffects(sid)
        assertEquals(1, effects.size)
        assertEquals("battle_shout", effects[0].id)
        assertEquals("Battle Shout", effects[0].name)
        assertEquals("STAT_BUFF", effects[0].type)
    }

    @Test
    fun `activeMobEffects returns correct snapshot`() {
        val h = buildSystem()
        h.spawnMob()
        h.registerRoot(durationMs = 4000)
        h.system.applyToMob(mobId, StatusEffectId("frost_grip"))

        val effects = h.system.activeMobEffects(mobId)
        assertEquals(1, effects.size)
        assertEquals("frost_grip", effects[0].id)
        assertEquals("ROOT", effects[0].type)
    }

    // ── StatModifiers Addition ──

    @Test
    fun `StatModifiers plus operator sums all fields`() {
        val a = StatModifiers(str = 1, dex = 2, con = 3, int = 4, wis = 5, cha = 6)
        val b = StatModifiers(str = 10, dex = 20, con = 30, int = 40, wis = 50, cha = 60)
        val sum = a + b
        assertEquals(11, sum.str)
        assertEquals(22, sum.dex)
        assertEquals(33, sum.con)
        assertEquals(44, sum.int)
        assertEquals(55, sum.wis)
        assertEquals(66, sum.cha)
    }
}
