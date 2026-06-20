package dev.ambon.engine

import dev.ambon.domain.DamageRange
import dev.ambon.domain.RaceDef
import dev.ambon.domain.RacialAbility
import dev.ambon.domain.RacialAbilityKind
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.CombatEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.TEST_ROOM_ID
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Covers the racial passive ability subsystem end-to-end through [CombatSystem.tick]: both trigger
 * types (low-health and would-be-lethal blow), the persisted cooldown gate, and the new
 * untargetable / mob-stun / damage-boost enforcement.
 *
 * The combat fixture uses deterministic bindings, so each player swing is exactly 1 damage and each
 * mob hit is its flat `DamageRange`. Players have base stats (no dodge), unarmored on both sides.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RacialAbilitySystemTest {
    private val raceId = "TESTRACE"

    private fun registryWith(ability: RacialAbility): RaceRegistry =
        RaceRegistry().also { it.register(RaceDef(id = raceId, displayName = "Test", racialAbility = ability)) }

    private fun enemyMob(
        damage: DamageRange = DamageRange(1, 1),
        hp: Int = 200,
    ): MobState = MobState(
        id = MobId("zone:goblin"),
        name = "a goblin",
        roomId = TEST_ROOM_ID,
        hp = hp,
        maxHp = hp,
        damage = damage,
    )

    private data class Engagement(
        val sid: SessionId,
        val mob: MobState,
        val combat: CombatSystem,
    )

    /** Logs a player of the test race into combat with [mob] and returns the live combat handle. */
    private suspend fun CombatTestFixture.engage(
        racial: RacialAbilitySystem,
        statusEffects: StatusEffectSystem? = null,
        playerMaxHp: Int,
        playerHp: Int,
        mob: MobState,
    ): Engagement {
        val combat = buildCombat(
            rng = Random(42),
            minDamage = 1,
            maxDamage = 1,
            statusEffects = statusEffects,
            racialAbilitySystem = racial,
        )
        val sid = SessionId(1L)
        players.loginOrFail(sid, "Hero")
        players.get(sid)!!.apply {
            race = raceId
            maxHp = playerMaxHp
            baseMaxHp = playerMaxHp
            hp = playerHp
        }
        mobs.upsert(mob)
        combat.startCombat(sid, "goblin")
        outbound.drainAll()
        return Engagement(sid, mob, combat)
    }

    // ── Low-health triggers ──────────────────────────────────────────────

    @Test
    fun `Pyrae immolation deals AoE damage to engaged enemies at low health`() = runTest {
        val fixture = CombatTestFixture()
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.PYRAE_IMMOLATE,
                    displayName = "Immolation",
                    cooldownMs = 120_000L,
                    triggerHealthPct = 10,
                    aoeDamagePctOfMaxHp = 0.6,
                    selfMessage = "You erupt in flame!",
                    roomMessage = "{player} erupts!",
                ),
            ),
        )
        val (sid, mob, combat) = fixture.engage(racial, playerMaxHp = 100, playerHp = 8, mob = enemyMob(hp = 200))

        fixture.tickCombat(combat)
        val texts = fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }

        assertTrue(texts.any { it.contains("engulfed in flames") }, "expected immolation AoE, got: $texts")
        assertTrue(mob.hp <= 200 - 60, "mob should take >= 60 AoE damage, hp=${mob.hp}")
        assertTrue(fixture.players.get(sid)!!.racialAbilityCooldownUntilMs > 0L, "cooldown should be set")
    }

    @Test
    fun `racial proc emits an AbilityCast combat event so the web client surfaces it`() = runTest {
        val fixture = CombatTestFixture()
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.PYRAE_IMMOLATE,
                    displayName = "Immolation",
                    cooldownMs = 120_000L,
                    triggerHealthPct = 10,
                    aoeDamagePctOfMaxHp = 0.6,
                    selfMessage = "You erupt in flame!",
                ),
            ),
        )
        val combatEvents = mutableListOf<Pair<SessionId, CombatEvent>>()
        racial.onCombatEvent = { sid, event -> combatEvents.add(sid to event) }
        val (sid, _, combat) = fixture.engage(racial, playerMaxHp = 100, playerHp = 8, mob = enemyMob(hp = 200))

        fixture.tickCombat(combat)

        // The self message reaches the web client as a combat-log/canvas event carrying the same text,
        // not just the telnet-only SendText.
        val cast = combatEvents.map { it.second }.filterIsInstance<CombatEvent.AbilityCast>()
            .firstOrNull { it.text == "You erupt in flame!" }
        assertTrue(cast != null, "expected an AbilityCast carrying the self message, got: $combatEvents")
        assertEquals(sid, combatEvents.first { it.second is CombatEvent.AbilityCast }.first)
        assertTrue(cast!!.targetIsPlayer, "a self-proc targets the triggering player")
        assertEquals("racial:pyrae_immolate", cast.abilityId)
    }

    @Test
    fun `Pyrae immolation that kills the engaged mob still prompts the player`() = runTest {
        val fixture = CombatTestFixture()
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.PYRAE_IMMOLATE,
                    displayName = "Immolation",
                    cooldownMs = 120_000L,
                    triggerHealthPct = 10,
                    aoeDamagePctOfMaxHp = 0.6,
                ),
            ),
        )
        // 60-damage AoE on a 30-HP mob ends the fight during the mob-attack phase. handleMobDeath
        // clears the player's target, so the late prompt sweep can't cover them — dealRacialDamage
        // must prompt directly.
        val (sid, mob, combat) = fixture.engage(racial, playerMaxHp = 100, playerHp = 8, mob = enemyMob(hp = 30))

        fixture.tickCombat(combat)
        val events = fixture.outbound.drainAll()

        assertTrue(mob.hp <= 0, "immolation should have killed the mob")
        assertFalse(combat.isInCombat(sid), "the kill should end combat")
        assertTrue(
            events.any { it is OutboundEvent.SendPrompt && it.sessionId == sid },
            "a racial kill that ends combat must still prompt the player",
        )
    }

    @Test
    fun `Aurelia dazzle stuns the enemy so it skips its next attack`() = runTest {
        val fixture = CombatTestFixture()
        val statusEffects = fixture.buildStatusEffects(
            StatusEffectDefinition(StatusEffectId("dazzle_stun"), "Dazzled", "stun", durationMs = 60_000L),
        )
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.AURELIA_DAZZLE,
                    displayName = "Dazzle",
                    cooldownMs = 120_000L,
                    triggerHealthPct = 15,
                    stunStatusId = "dazzle_stun",
                ),
            ),
            statusEffects = statusEffects,
        )
        val (sid, mob, combat) =
            fixture.engage(racial, statusEffects, playerMaxHp = 100, playerHp = 12, mob = enemyMob(hp = 200))

        // Round 1: player survives the hit (12 -> 11) and dazzles the mob.
        fixture.tickCombat(combat)
        fixture.outbound.drainAll()
        assertTrue(statusEffects.hasMobEffect(mob.id, "stun"), "mob should be stunned by the dazzle")
        val hpAfterDazzle = fixture.players.get(sid)!!.hp

        // Round 2: the stunned mob forfeits its attack, so the player takes no further damage — but
        // it must still emit a prompt, or prompt-driven clients look idle through the stun.
        fixture.tickCombat(combat)
        val round2 = fixture.outbound.drainAll()
        assertEquals(hpAfterDazzle, fixture.players.get(sid)!!.hp, "stunned mob should not damage the player")
        assertTrue(
            round2.any { it is OutboundEvent.SendPrompt && it.sessionId == sid },
            "a stunned round should still prompt the engaged player",
        )
    }

    @Test
    fun `Ophirae wrath multiplies the player's outgoing damage`() = runTest {
        val fixture = CombatTestFixture()
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.OPHIRAE_WRATH,
                    displayName = "Wrath",
                    cooldownMs = 120_000L,
                    triggerHealthPct = 20,
                    damageMultiplier = 2.0,
                    buffDurationMs = 60_000L,
                ),
            ),
        )
        val (_, _, combat) = fixture.engage(racial, playerMaxHp = 100, playerHp = 18, mob = enemyMob(hp = 200))

        // Round 1: baseline swing is 1; wrath fires after surviving the hit.
        fixture.tickCombat(combat)
        fixture.outbound.drainAll()

        // Round 2: the buffed swing should now read 2 damage.
        fixture.tickCombat(combat)
        val texts = fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(texts.any { it.contains("hit a goblin for 2 damage") }, "expected doubled swing, got: $texts")
    }

    @Test
    fun `Mycorae spores summon between one and three pets`() = runTest {
        val fixture = CombatTestFixture()
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.MYCORAE_SPORES,
                    displayName = "Spores",
                    cooldownMs = 120_000L,
                    triggerHealthPct = 25,
                    petTemplateKey = "spore_mushroom",
                    petCountMin = 1,
                    petCountMax = 3,
                    petDurationMs = 12_000L,
                ),
            ),
            rng = Random(7),
        )
        val summoned = mutableListOf<String>()
        racial.summonRacialPet = { _, template, _, _ ->
            summoned.add(template)
            null
        }
        val (sid, _, combat) = fixture.engage(racial, playerMaxHp = 100, playerHp = 20, mob = enemyMob(hp = 200))

        fixture.tickCombat(combat)

        assertTrue(summoned.size in 1..3, "expected 1..3 spore pets, got ${summoned.size}")
        assertTrue(summoned.all { it == "spore_mushroom" }, "wrong pet template: $summoned")
        assertTrue(fixture.players.get(sid)!!.racialAbilityCooldownUntilMs > 0L)
    }

    // ── Lethal-blow triggers ─────────────────────────────────────────────

    @Test
    fun `Kitsarae reversal nullifies a killing blow and heals for its magnitude`() = runTest {
        val fixture = CombatTestFixture()
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.KITSARAE_REVERSAL,
                    displayName = "Reversal",
                    cooldownMs = 180_000L,
                ),
            ),
        )
        val (sid, _, combat) =
            fixture.engage(racial, playerMaxHp = 100, playerHp = 30, mob = enemyMob(damage = DamageRange(50, 50)))

        fixture.tickCombat(combat)

        // 30 HP, 50-damage killing blow -> end at 30 + 50 = 80, still fighting.
        assertEquals(80, fixture.players.get(sid)!!.hp, "blow should become a heal of equal size")
        assertTrue(combat.isInCombat(sid), "Kitsarae keeps fighting")
        assertTrue(fixture.players.get(sid)!!.racialAbilityCooldownUntilMs > 0L)
    }

    @Test
    fun `Aetherae phase survives the blow and the mob whiffs while untargetable`() = runTest {
        val fixture = CombatTestFixture()
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.AETHERAE_PHASE,
                    displayName = "Phase",
                    cooldownMs = 150_000L,
                    phaseTicks = 2,
                ),
            ),
        )
        val (sid, mob, combat) =
            fixture.engage(racial, playerMaxHp = 100, playerHp = 40, mob = enemyMob(damage = DamageRange(50, 50)))

        // Round 1: lethal blow phased — HP held at pre-hit value, player untargetable.
        fixture.tickCombat(combat)
        fixture.outbound.drainAll()
        assertEquals(40, fixture.players.get(sid)!!.hp, "phase keeps the pre-hit HP")
        assertTrue(fixture.players.get(sid)!!.untargetableUntilMs > fixture.clock.millis())
        assertTrue(combat.isInCombat(sid), "Aetherae stays engaged")

        // Round 2: mob can't target the phased player, so it whiffs and stays in combat.
        fixture.tickCombat(combat)
        assertEquals(40, fixture.players.get(sid)!!.hp, "untargetable player takes no damage")
        assertTrue(combat.isMobInCombat(mob.id), "mob stays in combat rather than disengaging")
    }

    @Test
    fun `Lithae stone form disengages, regenerates, roots, and turns untargetable`() = runTest {
        val fixture = CombatTestFixture()
        val statusEffects = fixture.buildStatusEffects(
            StatusEffectDefinition(StatusEffectId("stoneform_root"), "Stone Form", "root", durationMs = 4_000L),
        )
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.LITHAE_STONEFORM,
                    displayName = "Stone Form",
                    cooldownMs = 150_000L,
                    regenPctOfMaxHp = 0.2,
                    stoneStatusId = "stoneform_root",
                    stoneDurationMs = 4_000L,
                ),
            ),
            statusEffects = statusEffects,
        )
        val (sid, _, combat) = fixture.engage(
            racial,
            statusEffects,
            playerMaxHp = 100,
            playerHp = 30,
            mob = enemyMob(damage = DamageRange(50, 50)),
        )

        fixture.tickCombat(combat)

        val player = fixture.players.get(sid)!!
        assertEquals(50, player.hp, "stone form regenerates 20% of max on top of the negated blow")
        assertFalse(combat.isInCombat(sid), "stone form disengages combat")
        assertTrue(statusEffects.hasPlayerEffect(sid, "root"), "stone form roots the player in place")
        assertTrue(player.untargetableUntilMs > fixture.clock.millis(), "stone form is untargetable")
    }

    @Test
    fun `Lustriae timeslip survives when the extra attack kills the attacker`() = runTest {
        val fixture = CombatTestFixture()
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.LUSTRIAE_TIMESLIP,
                    displayName = "Timeslip",
                    cooldownMs = 150_000L,
                ),
            ),
        )
        // Mob at 2 HP: the player's normal swing drops it to 1, the timeslip swing finishes it.
        val (sid, mob, combat) =
            fixture.engage(racial, playerMaxHp = 100, playerHp = 30, mob = enemyMob(damage = DamageRange(50, 50), hp = 2))

        fixture.tickCombat(combat)

        assertEquals(30, fixture.players.get(sid)!!.hp, "the negated blow leaves the player at pre-hit HP")
        assertEquals(0, mob.hp, "the timeslip strike should fell the attacker")
        assertFalse(combat.isInCombat(sid), "combat ends once the attacker dies")
    }

    @Test
    fun `Lustriae timeslip still dies when the extra attack is not enough`() = runTest {
        val fixture = CombatTestFixture()
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.LUSTRIAE_TIMESLIP,
                    displayName = "Timeslip",
                    cooldownMs = 150_000L,
                ),
            ),
        )
        // Mob too healthy for one extra swing to kill — the player dies, but the cooldown still burns.
        val (sid, _, combat) =
            fixture.engage(racial, playerMaxHp = 100, playerHp = 30, mob = enemyMob(damage = DamageRange(50, 50), hp = 200))

        fixture.tickCombat(combat)
        val texts = fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }

        assertTrue(texts.any { it.contains("slain") }, "player should die, got: $texts")
        assertTrue(fixture.players.get(sid)!!.racialAbilityCooldownUntilMs > 0L, "timeslip still burns its cooldown")
    }

    // ── Cooldown gate ────────────────────────────────────────────────────

    @Test
    fun `a triggered ability does not fire again while on cooldown`() = runTest {
        val fixture = CombatTestFixture()
        val racial = fixture.buildRacialAbilities(
            registryWith(
                RacialAbility(
                    kind = RacialAbilityKind.PYRAE_IMMOLATE,
                    displayName = "Immolation",
                    cooldownMs = 120_000L,
                    triggerHealthPct = 10,
                    aoeDamagePctOfMaxHp = 0.6,
                ),
            ),
        )
        val (_, _, combat) = fixture.engage(racial, playerMaxHp = 100, playerHp = 8, mob = enemyMob(hp = 500))

        fixture.tickCombat(combat)
        fixture.outbound.drainAll()

        // Still at low HP a round later, but the cooldown (120s) blocks a re-trigger.
        fixture.tickCombat(combat)
        val texts = fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertFalse(texts.any { it.contains("engulfed in flames") }, "immolation must not re-fire on cooldown: $texts")
    }
}
