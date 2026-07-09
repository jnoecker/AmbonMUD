package dev.ambon.engine

import dev.ambon.config.AkathavaeConfig
import dev.ambon.config.AppConfig
import dev.ambon.config.AppConfigLoader
import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.abilities.AbilityEffect
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.abilities.AbilityRegistry
import dev.ambon.engine.abilities.AbilityRegistryLoader
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectRegistryLoader
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.MutableClock
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * The Akathavae illumination arts (#1394): four SELF/ALLY utility abilities
 * shipped in the default config with `requiredClass: AKATHAVAE`, granted
 * automatically on pledge and set aside on renounce. These tests load the
 * real `application.yaml` so a config regression fails here, not in play.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AkathavaeAbilityKitTest {
    /** id → levelRequired, as promised by the phased kit design. */
    private val kitLevels = mapOf(
        "chroniclers_focus" to 3,
        "soothing_presence" to 6,
        "keepers_ward" to 10,
        "muses_insight" to 14,
    )

    /** id → the stat its buff must raise (the ward is a shield, not a stat buff). */
    private val kitBuffStats = mapOf(
        "chroniclers_focus" to "INT",
        "soothing_presence" to "CHA",
        "muses_insight" to "WIS",
    )

    private val config: AppConfig = AppConfigLoader.load().validated()

    private fun loadedAbilityRegistry(): AbilityRegistry =
        AbilityRegistry().also { AbilityRegistryLoader.load(config.engine.abilities, it) }

    private fun loadedStatusRegistry(): StatusEffectRegistry =
        StatusEffectRegistry().also { StatusEffectRegistryLoader.load(config.engine.statusEffects, it) }

    // ── Config shape ─────────────────────────────────────────────────────

    @Test
    fun `the illumination arts ship in the default config as auto-granted non-hostile arts`() {
        val registry = loadedAbilityRegistry()
        for ((id, level) in kitLevels) {
            val art = registry.get(AbilityId(id))
            assertNotNull(art, "kit ability '$id' missing from the default config")
            assertEquals("AKATHAVAE", art!!.requiredClass, "'$id' must be pledge-gated")
            assertEquals(level, art.levelRequired, "'$id' level")
            assertEquals(0, art.skillPointCost, "'$id' must auto-grant on pledge (skillPointCost 0)")
            assertTrue(
                art.targetType == "self" || art.targetType == "ally",
                "'$id' must be non-hostile (pledge refuses enemy targets), got '${art.targetType}'",
            )
            assertTrue(art.effect is AbilityEffect.ApplyStatus, "'$id' must be an APPLY_STATUS ritual")
        }
        val hostileArts = registry.all().filter {
            it.requiredClass == "AKATHAVAE" && (it.targetType == "enemy" || it.targetType == "all_enemies")
        }
        assertTrue(hostileArts.isEmpty(), "no AKATHAVAE ability may target enemies: $hostileArts")
    }

    @Test
    fun `the art buffs are modest rituals with cooldowns at least as long as the buffs`() {
        val abilities = loadedAbilityRegistry()
        val statuses = loadedStatusRegistry()
        for (id in kitLevels.keys) {
            val art = abilities.get(AbilityId(id))!!
            val statusId = (art.effect as AbilityEffect.ApplyStatus).statusEffectId
            val status = statuses.get(statusId)
            assertNotNull(status, "'$id' references unknown status '${statusId.value}'")
            assertTrue(
                art.cooldownMs >= status!!.durationMs,
                "'$id' cooldown (${art.cooldownMs}) must be >= buff duration (${status.durationMs}) — ritual, not stat line",
            )
            val buffStat = kitBuffStats[id]
            if (buffStat != null) {
                assertEquals("stat_buff", status.effectType, "'$id' status type")
                val magnitude = status.statMods[buffStat]
                assertTrue(
                    magnitude in 2..4,
                    "'$id' must buff $buffStat modestly (+2..+4), got $magnitude in ${status.statMods}",
                )
            } else {
                assertEquals("shield", status.effectType, "keepers_ward must be an absorb shield")
                assertTrue(status.shieldAmount > 0, "keepers_ward shield must absorb something")
            }
        }
    }

    // ── Runtime behavior ─────────────────────────────────────────────────

    private val roomA = RoomId("test:room")

    private fun testWorld(): World = World(
        rooms = mapOf(roomA to Room(roomA, "The Test Room", "A room.", exits = emptyMap())),
        startRoom = roomA,
    )

    private class Setup(
        val fixture: CombatTestFixture,
        val clock: MutableClock,
        val statusEffects: StatusEffectSystem,
        val abilitySystem: AbilitySystem,
        val akathavae: AkathavaeSystem,
        val akConfig: AkathavaeConfig,
    )

    private fun setup(extraAbilities: List<AbilityDefinition> = emptyList()): Setup {
        val clock = MutableClock(1_000_000L)
        val fixture = CombatTestFixture(roomId = roomA, clock = clock)
        val combat = fixture.buildCombat(rng = Random(1))
        val statusEffects = StatusEffectSystem(
            registry = loadedStatusRegistry(),
            players = fixture.players,
            mobs = fixture.mobs,
            outbound = fixture.outbound,
            clock = clock,
            rng = Random(1),
        )
        val abilityRegistry = loadedAbilityRegistry()
        extraAbilities.forEach { abilityRegistry.register(it) }
        val abilitySystem = AbilitySystem(
            players = fixture.players,
            registry = abilityRegistry,
            outbound = fixture.outbound,
            combat = combat,
            clock = clock,
            rng = Random(1),
            statusEffects = statusEffects,
        )
        val akConfig = AkathavaeConfig()
        val akathavae = AkathavaeSystem(
            players = fixture.players,
            items = fixture.items,
            world = testWorld(),
            outbound = fixture.outbound,
            combat = combat,
            statusEffects = statusEffects,
            clock = clock,
            rng = Random(1),
            config = akConfig,
        )
        return Setup(fixture, clock, statusEffects, abilitySystem, akathavae, akConfig)
    }

    private suspend fun loginPledged(s: Setup, sid: SessionId, level: Int): PlayerState {
        s.fixture.players.loginOrFail(sid, "Thalen")
        s.fixture.players.setLevel(sid, level)
        val me = s.fixture.players.get(sid)!!
        me.isAkathavae = true
        me.playerClass = "AKATHAVAE"
        me.unlockedClasses.add("AKATHAVAE")
        s.abilitySystem.refreshKnownAbilities(sid)
        me.mana = me.maxMana
        s.fixture.outbound.drainAll()
        return me
    }

    @Test
    fun `a buffed success stat measurably raises illumination success`() = runTest {
        val s = setup()
        val sid = SessionId(1)
        val me = loginPledged(s, sid, level = 5)

        fun successPct(): Int {
            val stats = resolvePlayerStats(me, s.fixture.items, s.statusEffects)
            return s.akathavae.illuminationSuccessPct(
                statValue = stats[s.akConfig.successStat],
                reliefStatValue = stats[s.akConfig.gapReliefStat],
                playerLevel = me.level,
                mobLevel = me.level,
            )
        }

        val baseline = successPct()
        assertTrue(
            s.statusEffects.applyToPlayer(sid, StatusEffectId("chroniclers_focus_buff")),
            "the focus buff should apply",
        )
        // +3 INT at successPerStatPoint 2.0 → +6% success.
        assertEquals(baseline + 6, successPct(), "buffed INT must raise illumination success")

        // The ritual fades: after the buff expires the odds fall back to baseline.
        s.clock.advance(61_000)
        s.statusEffects.tick(s.clock.millis())
        assertEquals(baseline, successPct(), "an expired focus buff must not keep helping")
    }

    @Test
    fun `a pledged keeper casts the kit on themselves`() = runTest {
        val s = setup()
        val sid = SessionId(1)
        loginPledged(s, sid, level = 10)

        val knownIds = s.abilitySystem.knownAbilities(sid).map { it.id.value }.toSet()
        assertEquals(
            setOf("chroniclers_focus", "soothing_presence", "keepers_ward"),
            knownIds,
            "a level-10 pledge should know the first three arts",
        )

        val err = s.abilitySystem.cast(sid, "chroniclers_focus", null)
        assertNull(err, "self-cast utility must stay allowed under the pledge, got: $err")
        val active = s.statusEffects.activePlayerEffects(sid).map { it.name }
        assertTrue(active.contains("Chronicler's Focus"), "buff should be active, got $active")
    }

    @Test
    fun `hostile casts stay refused for the pledged`() = runTest {
        // A classless enemy-targeted spell is auto-known even while pledged, so
        // it exercises the pledge gate itself rather than class filtering.
        val hostile = AbilityDefinition(
            id = AbilityId("test_bolt"),
            displayName = "Test Bolt",
            description = "A test bolt.",
            manaCostPct = 0.0,
            cooldownMs = 0,
            levelRequired = 1,
            skillPointCost = 0,
            targetType = "enemy",
            effect = AbilityEffect.DirectDamage(DamageRange(1, 2)),
            requiredClass = null,
        )
        val s = setup(extraAbilities = listOf(hostile))
        val sid = SessionId(1)
        loginPledged(s, sid, level = 5)

        val err = s.abilitySystem.cast(sid, "test_bolt", "rat")
        assertNotNull(err, "hostile cast must be refused under the pledge")
        assertTrue(err!!.contains("pledge stays your hand"), "got=$err")
    }
}
