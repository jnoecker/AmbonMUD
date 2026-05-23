package dev.ambon.engine.abilities

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.test.AbilityTestFixture
import dev.ambon.test.MutableClock
import dev.ambon.test.TEST_ROOM_ID
import dev.ambon.test.TEST_SESSION_ID
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

@OptIn(ExperimentalCoroutinesApi::class)
class AbilitySystemCompositeTest {
    private val roomId = TEST_ROOM_ID
    private val sid = TEST_SESSION_ID

    @Test
    fun `composite enemy spell deals damage and applies status with mana deducted once`() =
        runTest {
            val h = buildHarness()
            h.players.loginOrFail(sid, "Caster")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 20

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 50, maxHp = 50)
            h.mobs.upsert(mob)
            h.outbound.drainAll()

            val err = h.abilitySystem.cast(sid, "fire_bolt", "rat")
            assertNull(err)

            // Damage applied (5 from DirectDamage)
            assertEquals(45, mob.hp)
            // Status applied
            assertTrue(h.statusEffects.activeMobEffects(mob.id).any { it.id == "ignite" })
            // Mana deducted ONCE (40% of 20 = 8), not twice
            assertEquals(12, player.mana)

            val messages =
                h.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .map { it.text }
            assertTrue(messages.any { it.contains("Fire Bolt hits a rat for 5 damage") })
            assertTrue(messages.any { it.contains("Fire Bolt afflicts a rat") })
        }

    @Test
    fun `composite self spell heals and applies buff with cooldown set once`() =
        runTest {
            val h = buildHarness()
            h.players.loginOrFail(sid, "Cleric")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 20
            player.hp = 5
            h.outbound.drainAll()

            val err = h.abilitySystem.cast(sid, "blessing", null)
            assertNull(err)

            assertEquals(10, player.hp)
            assertTrue(h.statusEffects.activePlayerEffects(sid).any { it.id == "shield" })
            assertEquals(12, player.mana)

            // Cooldown should be set (single cooldown on the ability, not per sub-effect)
            val ability = h.registry.findByKeyword("blessing")!!
            val cdRemaining = h.abilitySystem.cooldownRemainingMs(sid, ability.id)
            assertTrue(cdRemaining > 0L, "Composite ability should set cooldown")
        }

    @Test
    fun `composite with target-incompatible child returns misconfigured error`() =
        runTest {
            val h = buildHarness()
            h.players.loginOrFail(sid, "Caster")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 20

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)

            // bad_mix targets ENEMY but composes DirectDamage with DirectHeal
            // (heal is not valid for enemy targets).
            val err = h.abilitySystem.cast(sid, "bad_mix", "rat")
            assertNotNull(err)
            assertTrue(err!!.contains("misconfigured"), "got: $err")
            // Mana should NOT be deducted on validation failure
            assertEquals(20, player.mana)
            // Mob unharmed
            assertEquals(20, mob.hp)
        }

    @Test
    fun `flatten unwraps nested composites`() {
        val inner = AbilityEffect.Composite(
            effects = listOf(
                AbilityEffect.DirectDamage(DamageRange(1, 1)),
                AbilityEffect.ApplyStatus(StatusEffectId("ignite")),
            ),
        )
        val outer = AbilityEffect.Composite(
            effects = listOf(
                inner,
                AbilityEffect.Taunt(flatThreat = 1.0, margin = 1.0),
            ),
        )
        val flat = outer.flatten()
        assertEquals(3, flat.size)
        assertTrue(flat[0] is AbilityEffect.DirectDamage)
        assertTrue(flat[1] is AbilityEffect.ApplyStatus)
        assertTrue(flat[2] is AbilityEffect.Taunt)
    }

    @Test
    fun `primaryEffectType returns first leaf type for composites`() {
        val composite = AbilityEffect.Composite(
            effects = listOf(
                AbilityEffect.DirectDamage(DamageRange(5, 5)),
                AbilityEffect.ApplyStatus(StatusEffectId("ignite")),
            ),
        )
        assertEquals("DIRECT_DAMAGE", composite.primaryEffectType())
        assertEquals("COMPOSITE", composite.toEffectType())
    }

    private fun buildHarness(
        clock: MutableClock = MutableClock(0L),
        rng: Random = Random(42),
    ): CompositeHarness {
        val fixture = AbilityTestFixture(roomId = roomId, clock = clock, rng = rng)
        val statusRegistry = StatusEffectRegistry()
        statusRegistry.register(
            StatusEffectDefinition(
                id = StatusEffectId("ignite"),
                displayName = "Ignite",
                effectType = "dot",
                durationMs = 6000,
                tickIntervalMs = 2000,
                tickMinValue = 5,
                tickMaxValue = 5,
            ),
        )
        statusRegistry.register(
            StatusEffectDefinition(
                id = StatusEffectId("shield"),
                displayName = "Shield",
                effectType = "shield",
                durationMs = 30000,
                shieldAmount = 20,
                stackBehavior = "none",
            ),
        )
        val statusEffects =
            StatusEffectSystem(
                registry = statusRegistry,
                players = fixture.players,
                mobs = fixture.mobs,
                outbound = fixture.outbound,
                clock = fixture.clock,
                rng = rng,
                dirtyNotifier = DirtyNotifier.NO_OP,
            )
        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("fire_bolt"),
                displayName = "Fire Bolt",
                description = "Hits and burns.",
                manaCostPct = 40.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.Composite(
                    effects = listOf(
                        AbilityEffect.DirectDamage(DamageRange(5, 5)),
                        AbilityEffect.ApplyStatus(StatusEffectId("ignite")),
                    ),
                ),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("blessing"),
                displayName = "Blessing",
                description = "Heal and shield.",
                manaCostPct = 40.0,
                cooldownMs = 5000,
                levelRequired = 1,
                targetType = "self",
                effect = AbilityEffect.Composite(
                    effects = listOf(
                        AbilityEffect.DirectHeal(minHeal = 5, maxHeal = 5),
                        AbilityEffect.ApplyStatus(StatusEffectId("shield")),
                    ),
                ),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("bad_mix"),
                displayName = "Bad Mix",
                description = "Mismatched target/effect.",
                manaCostPct = 40.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.Composite(
                    effects = listOf(
                        AbilityEffect.DirectDamage(DamageRange(5, 5)),
                        AbilityEffect.DirectHeal(minHeal = 5, maxHeal = 5),
                    ),
                ),
            ),
        )
        val abilitySystem = fixture.buildAbilitySystem(
            registry = registry,
            statusEffects = statusEffects,
        )
        return CompositeHarness(
            players = fixture.players,
            mobs = fixture.mobs,
            outbound = fixture.outbound,
            abilitySystem = abilitySystem,
            statusEffects = statusEffects,
            registry = registry,
        )
    }

    private data class CompositeHarness(
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val outbound: LocalOutboundBus,
        val abilitySystem: AbilitySystem,
        val statusEffects: StatusEffectSystem,
        val registry: AbilityRegistry,
    )
}
