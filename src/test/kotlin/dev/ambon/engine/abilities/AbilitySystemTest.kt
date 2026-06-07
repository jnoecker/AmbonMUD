package dev.ambon.engine.abilities

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.LevelRewardsConfig
import dev.ambon.config.ProgressionConfig
import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AbilitySystemTest {
    private val roomId = TEST_ROOM_ID
    private val sid = TEST_SESSION_ID

    private fun buildSystem(
        clock: MutableClock = MutableClock(0L),
        rng: Random = Random(42),
    ): TestHarness {
        val fixture = AbilityTestFixture(roomId = roomId, clock = clock, rng = rng)
        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("magic_missile"),
                displayName = "Magic Missile",
                description = "A bolt of arcane energy.",
                // 40% of 20 baseMana at L1 â†’ 8 mana â€” matches old flat cost.
                manaCostPct = 40.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(5, 5)),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("heal"),
                displayName = "Heal",
                description = "Restore HP.",
                manaCostPct = 50.0,
                cooldownMs = 5000,
                levelRequired = 1,
                targetType = "self",
                effect = AbilityEffect.DirectHeal(minHeal = 5, maxHeal = 5),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("fireball"),
                displayName = "Fireball",
                description = "Fire!",
                manaCostPct = 75.0,
                cooldownMs = 3000,
                levelRequired = 5,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(10, 10)),
            ),
        )
        val abilitySystem = fixture.buildAbilitySystem(registry = registry)
        return TestHarness(
            players = fixture.players,
            mobs = fixture.mobs,
            items = fixture.items,
            outbound = fixture.outbound,
            combat = fixture.combat,
            registry = registry,
            abilitySystem = abilitySystem,
            clock = fixture.clock,
        )
    }

    private data class TestHarness(
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val items: ItemRegistry,
        val outbound: LocalOutboundBus,
        val combat: CombatSystem,
        val registry: AbilityRegistry,
        val abilitySystem: AbilitySystem,
        val clock: MutableClock,
    )

    @Test
    fun `cast damage spell reduces mob hp and deducts mana`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Caster")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 20

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)
            h.outbound.drainAll()

            val err = h.abilitySystem.cast(sid, "magic_missile", "rat")
            assertNull(err)

            assertEquals(12, player.mana)
            assertEquals(15, mob.hp)

            val messages =
                h.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .map { it.text }
            assertTrue(messages.any { it.contains("Magic Missile hits a rat for 5 damage") })
        }

    @Test
    fun `cast heal restores hp and deducts mana`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Healer")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 20
            player.hp = 5
            h.outbound.drainAll()

            val err = h.abilitySystem.cast(sid, "heal", null)
            assertNull(err)

            assertEquals(10, player.mana)
            assertEquals(10, player.hp)

            val messages =
                h.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .map { it.text }
            assertTrue(messages.any { it.contains("Heal heals you for 5 HP") })
        }

    @Test
    fun `computeManaCost scales with player level off the base mana pool`() =
        runTest {
            // Custom progression with a non-trivial manaScalingRate so the base pool
            // grows with level â€” the test bakery default uses rate=1.0 (flat pool).
            val fixture = AbilityTestFixture(roomId = roomId, clock = MutableClock(0L), rng = Random(42))
            val progression = PlayerProgression(
                config = ProgressionConfig(
                    rewards = LevelRewardsConfig(baseMana = 20, manaScalingRate = 1.10),
                ),
                bindings = StatBindingsConfig(),
            )
            val registry = AbilityRegistry()
            registry.register(
                AbilityDefinition(
                    id = AbilityId("zap"),
                    displayName = "Zap",
                    description = "",
                    manaCostPct = 40.0,
                    cooldownMs = 0,
                    levelRequired = 1,
                    targetType = "enemy",
                    effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                ),
            )
            val abilitySystem = fixture.buildAbilitySystem(registry = registry, progression = progression)
            fixture.players.loginOrFail(sid, "Scaler")
            val player = fixture.players.get(sid)!!
            val ability = registry.findByKeyword("zap")!!

            // 40% of the level-1 base pool (20) â†’ 8 mana, matching the old flat cost.
            player.level = 1
            val costL1 = abilitySystem.computeManaCost(player, ability)
            assertEquals(8, costL1)

            // Bumping level grows the base pool via manaScalingRate, so the same
            // percentage costs strictly more mana â€” the core scaling contract.
            player.level = 20
            val costL20 = abilitySystem.computeManaCost(player, ability)
            assertTrue(
                costL20 > costL1,
                "Expected mana cost to grow with level (L1=$costL1, L20=$costL20)",
            )
        }

    @Test
    fun `cast fails with insufficient mana`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "LowMana")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 5

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)

            val err = h.abilitySystem.cast(sid, "magic_missile", "rat")
            assertNotNull(err)
            assertTrue(err!!.contains("Not enough mana"))
            assertEquals(5, player.mana)
        }

    @Test
    fun `cast fails when spell on cooldown`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Cooldown")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 50
            player.hp = 5
            h.outbound.drainAll()

            // First cast should work
            val err1 = h.abilitySystem.cast(sid, "heal", null)
            assertNull(err1)

            // Second cast immediately should fail (5s cooldown)
            val err2 = h.abilitySystem.cast(sid, "heal", null)
            assertNotNull(err2)
            assertTrue(err2!!.contains("cooldown"))

            // Advance past cooldown
            h.clock.advance(6_000L)
            val player2 = h.players.get(sid)!!
            player2.hp = 5
            val err3 = h.abilitySystem.cast(sid, "heal", null)
            assertNull(err3)
        }

    @Test
    fun `unknown spell returns error`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Unknown")
            h.abilitySystem.syncAbilities(sid, 1)

            val err = h.abilitySystem.cast(sid, "thunderbolt", "rat")
            assertNotNull(err)
            assertTrue(err!!.contains("don't know"))
        }

    @Test
    fun `level-gated spell not available at low level`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "LowLevel")
            h.abilitySystem.syncAbilities(sid, 1)

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)

            // fireball requires level 5
            val err = h.abilitySystem.cast(sid, "fireball", "rat")
            assertNotNull(err)
            assertTrue(err!!.contains("don't know"))
        }

    @Test
    fun `level-gated spell available at correct level`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "HighLevel")
            h.abilitySystem.syncAbilities(sid, 5)
            val player = h.players.get(sid)!!
            player.mana = 30

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)

            val err = h.abilitySystem.cast(sid, "fireball", "rat")
            assertNull(err)
            assertEquals(15, player.mana)
            assertEquals(10, mob.hp)
        }

    @Test
    fun `spell kills mob triggers handleSpellKill`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Killer")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 20

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 3, maxHp = 20)
            h.mobs.upsert(mob)
            h.outbound.drainAll()

            val err = h.abilitySystem.cast(sid, "magic_missile", "rat")
            assertNull(err)

            // Mob should be dead
            assertNull(h.mobs.get(MobId("zone:rat")))
            val messages =
                h.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .map { it.text }
            assertTrue(messages.any { it.contains("dies") })
        }

    @Test
    fun `cast resolves a multi-word spell name with a trailing target`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Caster")
            h.abilitySystem.syncAbilities(sid, 1)
            h.players.get(sid)!!.mana = 20

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)

            // The parser splits "cast magic missile rat" into spell="magic",
            // target="missile rat" — the longest known-spell prefix must win (#1221).
            val err = h.abilitySystem.cast(sid, "magic", "missile rat")
            assertNull(err)
            assertEquals(15, mob.hp)
        }

    @Test
    fun `cast with a full multi-word spell name and no target prompts for one`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Caster")
            h.abilitySystem.syncAbilities(sid, 1)
            h.players.get(sid)!!.mana = 20

            // "cast magic missile" out of combat: the whole input is the spell
            // name, not spell "magic" with target "missile".
            val err = h.abilitySystem.cast(sid, "magic", "missile")
            assertEquals("Cast Magic Missile on whom?", err)
        }

    @Test
    fun `cast at an out-of-combat mob engages combat so the target fights back`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Provoker")
            h.abilitySystem.syncAbilities(sid, 1)
            h.players.get(sid)!!.mana = 20

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)

            assertFalse(h.combat.isInCombat(sid))
            val err = h.abilitySystem.cast(sid, "magic_missile", "rat")
            assertNull(err)

            assertTrue(h.combat.isInCombat(sid), "a hostile cast must engage combat with its target")
            assertEquals(mob.id, h.combat.currentTarget(sid))
        }

    @Test
    fun `cast that kills its target does not leave the player stuck in combat`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Slayer")
            h.abilitySystem.syncAbilities(sid, 1)
            h.players.get(sid)!!.mana = 20

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 3, maxHp = 20)
            h.mobs.upsert(mob)

            val err = h.abilitySystem.cast(sid, "magic_missile", "rat")
            assertNull(err)

            assertNull(h.mobs.get(MobId("zone:rat")))
            assertFalse(h.combat.isInCombat(sid), "combat must end when the spell kills the target")
        }

    @Test
    fun `cast while fighting another mob does not retarget`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Splitter")
            h.abilitySystem.syncAbilities(sid, 1)
            h.players.get(sid)!!.mana = 20

            val first = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            val second = MobState(MobId("zone:bat"), "a bat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(first)
            h.mobs.upsert(second)
            assertNull(h.combat.startCombat(sid, "rat"))

            val err = h.abilitySystem.cast(sid, "magic_missile", "bat")
            assertNull(err)

            assertEquals(first.id, h.combat.currentTarget(sid), "off-target cast must not switch the melee target")
            assertEquals(15, second.hp)
        }

    @Test
    fun `knownAbilities reflects level`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Lister")
            h.abilitySystem.syncAbilities(sid, 1)

            val level1 = h.abilitySystem.knownAbilities(sid)
            assertEquals(2, level1.size) // magic_missile and heal

            h.abilitySystem.syncAbilities(sid, 5)
            val level5 = h.abilitySystem.knownAbilities(sid)
            assertEquals(3, level5.size) // + fireball
        }

    @Test
    fun `cast enemy spell without target and not in combat returns error`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "NoTarget")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 20

            val err = h.abilitySystem.cast(sid, "magic_missile", null)
            assertNotNull(err)
            assertTrue(err!!.contains("on whom"))
        }

    @Test
    fun `session cleanup removes learned abilities and cooldowns`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Cleanup")
            h.abilitySystem.syncAbilities(sid, 1)

            assertTrue(h.abilitySystem.knownAbilities(sid).isNotEmpty())
            h.abilitySystem.onPlayerDisconnected(sid)
            assertTrue(h.abilitySystem.knownAbilities(sid).isEmpty())
        }

    @Test
    fun `loadAbilities grants race abilities`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "RaceGift")

            h.abilitySystem.loadAbilities(
                sessionId = sid,
                learnedIds = emptySet(),
                raceAbilityIds = setOf("magic_missile"),
            )

            assertTrue("magic_missile" in h.abilitySystem.knownAbilityIds(sid))
        }

    @Test
    fun `setRaceAbilities revokes previous grants and adds new ones`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Swapper")
            h.abilitySystem.loadAbilities(
                sessionId = sid,
                learnedIds = emptySet(),
                raceAbilityIds = setOf("magic_missile"),
            )

            h.abilitySystem.setRaceAbilities(sid, setOf("heal"))

            val known = h.abilitySystem.knownAbilityIds(sid)
            assertTrue("heal" in known)
            assertFalse(
                "magic_missile" in known,
                "previous race ability should be revoked",
            )
        }

    @Test
    fun `setRaceAbilities preserves abilities also learned from trainer`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "Doubled")
            // Player has magic_missile both as a race grant and as a trainer-learned ability.
            h.abilitySystem.loadAbilities(
                sessionId = sid,
                learnedIds = setOf("magic_missile"),
                raceAbilityIds = setOf("magic_missile"),
            )

            // Swap to a race that grants nothing â€” trainer-learned ability must survive.
            h.abilitySystem.setRaceAbilities(sid, emptySet())

            assertTrue(
                "magic_missile" in h.abilitySystem.knownAbilityIds(sid),
                "trainer-learned ability must survive race swap",
            )
        }

    @Test
    fun `learnAbility allows learning an ability currently granted only by race`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "DoubleUp")
            val me = h.players.get(sid)!!
            me.level = 5
            me.unlockedClasses.add("WARRIOR")
            h.abilitySystem.loadAbilities(
                sessionId = sid,
                learnedIds = emptySet(),
                raceAbilityIds = setOf("magic_missile"),
            )

            val error = h.abilitySystem.learnAbility(
                sessionId = sid,
                abilityId = AbilityId("magic_missile"),
                level = me.level,
                unlockedClasses = me.unlockedClasses,
                skillPointInterval = 1,
                learnedIds = emptySet(),
            )

            assertNull(error, "trainer should allow learning a race-only ability; got: $error")
        }

    @Test
    fun `session cleanup clears race-granted abilities`() =
        runTest {
            val h = buildSystem()
            h.players.loginOrFail(sid, "CleanupRace")
            h.abilitySystem.loadAbilities(
                sessionId = sid,
                learnedIds = emptySet(),
                raceAbilityIds = setOf("magic_missile"),
            )
            assertTrue(h.abilitySystem.knownAbilities(sid).isNotEmpty())

            h.abilitySystem.onPlayerDisconnected(sid)

            assertTrue(h.abilitySystem.knownAbilities(sid).isEmpty())
        }

    @Test
    fun `cast APPLY_STATUS on enemy mob applies effect`() =
        runTest {
            val h = buildSystemWithStatusEffects()
            h.players.loginOrFail(sid, "Caster")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 30

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)
            h.outbound.drainAll()

            val err = h.abilitySystem.cast(sid, "ignite", "rat")
            assertNull(err)

            assertTrue(player.mana < 30, "Mana should be deducted")
            assertTrue(
                h.statusEffects.hasMobEffect(mob.id, "dot"),
                "DOT should be applied to mob",
            )
        }

    @Test
    fun `cast APPLY_STATUS on self applies effect to player`() =
        runTest {
            val h = buildSystemWithStatusEffects()
            h.players.loginOrFail(sid, "Buffer")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 30
            h.outbound.drainAll()

            val err = h.abilitySystem.cast(sid, "shield", null)
            assertNull(err)

            assertTrue(player.mana < 30, "Mana should be deducted")
            assertTrue(
                h.statusEffects.hasPlayerEffect(sid, "shield"),
                "SHIELD should be applied to player",
            )
        }

    @Test
    fun `AreaDamage does not consume mana when no enemies in combat`() =
        runTest {
            val h = buildAreaDamageSystem()
            h.players.loginOrFail(sid, "Mage")
            h.abilitySystem.syncAbilities(sid, 5)
            val player = h.players.get(sid)!!
            player.mana = 50

            // Mob in room but NOT in combat
            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)
            h.outbound.drainAll()

            val err = h.abilitySystem.cast(sid, "area_blast", "rat")
            assertNotNull(err)
            assertTrue(err!!.contains("No enemies in combat"))
            assertEquals(50, player.mana, "Mana should not be consumed on failed AreaDamage")
        }

    @Test
    fun `Taunt does not consume mana when mob is not in combat`() =
        runTest {
            val h = buildAreaDamageSystem()
            h.players.loginOrFail(sid, "Tank")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 50

            // Mob in room but NOT in combat
            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)
            h.outbound.drainAll()

            val err = h.abilitySystem.cast(sid, "taunt_ability", "rat")
            assertNotNull(err)
            assertTrue(err!!.contains("not in combat"))
            assertEquals(50, player.mana, "Mana should not be consumed on failed Taunt")
        }

    private fun buildAreaDamageSystem(
        clock: MutableClock = MutableClock(0L),
        rng: Random = Random(42),
    ): TestHarness {
        val fixture = AbilityTestFixture(roomId = roomId, clock = clock, rng = rng)
        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("area_blast"),
                displayName = "Area Blast",
                description = "Hits all enemies in combat.",
                manaCostPct = 25.0,
                cooldownMs = 0,
                levelRequired = 5,
                targetType = "enemy",
                effect = AbilityEffect.AreaDamage(damage = DamageRange(3, 7)),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("taunt_ability"),
                displayName = "Taunt",
                description = "Force mob to attack you.",
                manaCostPct = 25.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.Taunt(flatThreat = 50.0, margin = 10.0),
            ),
        )
        val abilitySystem = fixture.buildAbilitySystem(registry = registry, mobsForAbility = fixture.mobs)
        return TestHarness(
            players = fixture.players,
            mobs = fixture.mobs,
            items = fixture.items,
            outbound = fixture.outbound,
            combat = fixture.combat,
            registry = registry,
            abilitySystem = abilitySystem,
            clock = fixture.clock,
        )
    }

    private fun buildSystemWithStatusEffects(
        clock: MutableClock = MutableClock(0L),
        rng: Random = Random(42),
    ): StatusTestHarness {
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
                id = AbilityId("ignite"),
                displayName = "Ignite",
                description = "Burns the target.",
                manaCostPct = 50.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.ApplyStatus(StatusEffectId("ignite")),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("shield"),
                displayName = "Shield",
                description = "Grants a shield.",
                manaCostPct = 50.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "self",
                effect = AbilityEffect.ApplyStatus(StatusEffectId("shield")),
            ),
        )
        val abilitySystem = fixture.buildAbilitySystem(registry = registry, statusEffects = statusEffects)
        return StatusTestHarness(
            players = fixture.players,
            mobs = fixture.mobs,
            outbound = fixture.outbound,
            abilitySystem = abilitySystem,
            statusEffects = statusEffects,
            clock = fixture.clock,
        )
    }

    private data class StatusTestHarness(
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val outbound: LocalOutboundBus,
        val abilitySystem: AbilitySystem,
        val statusEffects: StatusEffectSystem,
        val clock: MutableClock,
    )
}
