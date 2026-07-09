package dev.ambon.engine

import dev.ambon.config.PetConfig
import dev.ambon.config.PetSpellConfig
import dev.ambon.config.PetTemplateConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Regression tests for issue #1398: pets share their owner's Akathavae pledge.
 *
 * A pledged owner's pet must never reduce a mob's HP — no melee swings, no
 * damaging auto-cast skills, no manually triggered damaging skills (direct
 * damage or DOT). Purely defensive utility (taunts) stays available so a tank
 * pet can still soak hits, mirroring the owner rule ("dodge, flee, or fall,
 * and never deal damage").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PetAkathavaePledgeTest {
    private val biteSkill = PetSpellConfig(
        displayName = "Bite",
        message = "{pet} bites {target}",
        roomMessage = "{pet} bites {target}",
        minDamage = 4,
        maxDamage = 4,
        statusEffectId = "bleed",
        cooldownMs = 6_000L,
        weight = 2,
    )

    // DOT-only skill: no direct damage roll, but the applied status ticks damage.
    private val venomSkill = PetSpellConfig(
        displayName = "Venom",
        message = "{pet} envenoms {target}",
        roomMessage = "{pet} envenoms {target}",
        statusEffectId = "poison",
        cooldownMs = 6_000L,
        weight = 1,
    )

    // Defensive taunt: threat only, deals no damage.
    private val roarSkill = PetSpellConfig(
        displayName = "Roar",
        message = "{pet} roars at {target}",
        roomMessage = "{pet} roars at {target}",
        threatBonus = 100.0,
        cooldownMs = 15_000L,
        weight = 1,
    )

    private val petConfig = PetConfig(
        manualSkillGraceMs = 8_000L,
        definitions = mapOf(
            "wolf_companion" to PetTemplateConfig(
                name = "a wolf companion",
                baseHp = 25,
                baseMinDamage = 2,
                baseMaxDamage = 2,
                baseArmor = 0,
                spells = mapOf("bite" to biteSkill, "venom" to venomSkill),
            ),
            "bear_guardian" to PetTemplateConfig(
                name = "a bear guardian",
                baseHp = 40,
                baseMinDamage = 5,
                baseMaxDamage = 5,
                baseArmor = 2,
                threatMultiplier = 2.0,
                spells = mapOf("roar" to roarSkill),
            ),
        ),
    )

    private val ownerStats = PetSystem.OwnerStats(maxHp = 30, damageMin = 1, damageMax = 3, armor = 0)

    private data class Fixture(
        val base: CombatTestFixture,
        val combat: CombatSystem,
        val pets: PetSystem,
        val statusEffects: StatusEffectSystem,
    )

    private fun newFixture(): Fixture {
        val fixture = CombatTestFixture()
        val pets = PetSystem(petConfig, fixture.mobs, fixture.clock)
        val bleed = StatusEffectDefinition(
            id = StatusEffectId("bleed"),
            displayName = "Bleeding",
            effectType = "dot",
            durationMs = 8_000L,
            tickIntervalMs = 2_000L,
            tickMinValue = 2,
            tickMaxValue = 4,
        )
        val poison = StatusEffectDefinition(
            id = StatusEffectId("poison"),
            displayName = "Poisoned",
            effectType = "dot",
            durationMs = 8_000L,
            tickIntervalMs = 2_000L,
            tickMinValue = 2,
            tickMaxValue = 4,
        )
        val statusEffects = fixture.buildStatusEffects(bleed, poison)
        val combat = fixture.buildCombat(
            rng = Random(1),
            petSystem = pets,
            statusEffects = statusEffects,
        )
        return Fixture(fixture, combat, pets, statusEffects)
    }

    /**
     * Logs in a pledged (or unpledged) owner with a pet, then has the mob force
     * combat via [CombatSystem.startMobCombat] — the only way a pledged player
     * enters mob combat, since `kill` is refused under the pledge.
     */
    private suspend fun setupMobForcedCombat(
        fixture: Fixture,
        petTemplate: String,
        pledged: Boolean,
        mobHp: Int = 100,
    ): Triple<SessionId, MobState, MobState> {
        val sid = SessionId(7L)
        fixture.base.players.loginOrFail(sid, "Pilgrim")
        val player = fixture.base.players.get(sid)!!
        player.isAkathavae = pledged
        // Plenty of HP so mob swings across several ticks never kill the owner.
        player.maxHp = 1_000
        player.hp = 1_000
        val pet = fixture.pets.summon(sid, petTemplate, player.roomId, ownerStats)!!
        val target = MobState(
            MobId("demo:goblin"),
            "a goblin",
            fixture.base.roomId,
            hp = mobHp,
            maxHp = mobHp,
        )
        fixture.base.mobs.upsert(target)
        assertTrue(fixture.combat.startMobCombat(target.id, sid), "mob should force combat")
        fixture.base.outbound.drainAll()
        return Triple(sid, pet, target)
    }

    @Test
    fun `pledged owner's pet never melees or auto-casts damaging skills when a mob attacks`() = runTest {
        val f = newFixture()
        val (sid, _, target) = setupMobForcedCombat(f, "wolf_companion", pledged = true)
        val startHp = target.hp

        repeat(3) { f.base.tickCombat(f.combat) }

        assertEquals(startHp, f.base.mobs.get(target.id)!!.hp, "pledged owner's pet must not damage the mob")
        assertFalse(f.statusEffects.hasMobEffect(target.id, "dot"), "pledged owner's pet must not apply DOTs")
        val texts = f.base.outbound.drainAll()
            .filterIsInstance<OutboundEvent.SendText>()
            .map { it.text }
        assertTrue(
            texts.none { it.contains("a wolf companion hits") || it.contains("bites") || it.contains("envenoms") },
            "expected no pet attack output for pledged owner, got=$texts",
        )
        assertTrue(f.combat.isInCombat(sid), "owner stays in combat — the pledge only stays the pet's teeth")
    }

    @Test
    fun `pledged owner cannot manually trigger a direct-damage pet skill`() = runTest {
        val f = newFixture()
        val (sid, _, target) = setupMobForcedCombat(f, "wolf_companion", pledged = true)
        val startHp = target.hp

        val result = f.combat.triggerPetSkill(sid, "bite")

        assertTrue(result is CombatSystem.PetSkillResult.Error, "expected pledge refusal, got $result")
        result as CombatSystem.PetSkillResult.Error
        assertEquals("AKATHAVAE_PLEDGE", result.code)
        assertEquals(ERR_AKATHAVAE_PLEDGE, result.message)
        assertEquals(startHp, f.base.mobs.get(target.id)!!.hp, "refused skill must not deal damage")
    }

    @Test
    fun `pledged owner cannot manually trigger a dot-only pet skill`() = runTest {
        val f = newFixture()
        val (sid, _, target) = setupMobForcedCombat(f, "wolf_companion", pledged = true)

        val result = f.combat.triggerPetSkill(sid, "venom")

        assertTrue(result is CombatSystem.PetSkillResult.Error, "expected pledge refusal, got $result")
        assertEquals("AKATHAVAE_PLEDGE", (result as CombatSystem.PetSkillResult.Error).code)
        assertFalse(f.statusEffects.hasMobEffect(target.id, "dot"), "refused skill must not apply its DOT")
    }

    @Test
    fun `pledged owner may still trigger a defensive taunt skill`() = runTest {
        val f = newFixture()
        val (sid, pet, target) = setupMobForcedCombat(f, "bear_guardian", pledged = true)
        val startHp = target.hp

        val result = f.combat.triggerPetSkill(sid, "roar")

        assertEquals(CombatSystem.PetSkillResult.Ok, result)
        assertEquals(startHp, f.base.mobs.get(target.id)!!.hp, "taunt deals no damage")
        val petSid = f.pets.getPetSessionId(pet.id)
        assertNotNull(petSid)
        assertTrue(
            f.combat.threatTable.getThreat(target.id, petSid!!) >= 100.0,
            "tank pet keeps building threat so it can soak hits under the pledge",
        )
    }

    @Test
    fun `pledged owner's tank pet auto-casts taunt but never melees`() = runTest {
        val f = newFixture()
        val (_, pet, target) = setupMobForcedCombat(f, "bear_guardian", pledged = true)
        val startHp = target.hp

        // First tick auto-casts roar (defensive, allowed); later ticks have no eligible
        // skill and must skip the melee fallback entirely.
        repeat(3) { f.base.tickCombat(f.combat) }

        assertEquals(startHp, f.base.mobs.get(target.id)!!.hp, "tank pet must never melee under the pledge")
        val petSid = f.pets.getPetSessionId(pet.id)
        assertNotNull(petSid)
        assertTrue(
            f.combat.threatTable.getThreat(target.id, petSid!!) >= 100.0,
            "auto-cast roar should still generate taunt threat",
        )
    }

    @Test
    fun `non-pledged owner's pet still fights back unchanged`() = runTest {
        val f = newFixture()
        val (_, _, target) = setupMobForcedCombat(f, "wolf_companion", pledged = false)
        val startHp = target.hp

        f.base.tickCombat(f.combat)

        assertTrue(
            f.base.mobs.get(target.id)!!.hp < startHp,
            "unpledged owner's pet must keep dealing damage",
        )
    }
}
