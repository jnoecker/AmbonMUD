package dev.ambon.engine

import dev.ambon.config.PetConfig
import dev.ambon.config.PetSpellConfig
import dev.ambon.config.PetTemplateConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.test.CombatTestFixture
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
class PetSkillTest {
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
    private val pounceSkill = PetSpellConfig(
        displayName = "Pounce",
        message = "{pet} pounces on {target}",
        roomMessage = "{pet} pounces on {target}",
        minDamage = 6,
        maxDamage = 6,
        statusEffectId = "concuss",
        cooldownMs = 12_000L,
        weight = 1,
    )
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
                spells = mapOf("bite" to biteSkill, "pounce" to pounceSkill),
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
            stackBehavior = "stack",
            maxStacks = 3,
        )
        val concuss = StatusEffectDefinition(
            id = StatusEffectId("concuss"),
            displayName = "Concuss",
            effectType = "stun",
            durationMs = 2_000L,
            stackBehavior = "none",
        )
        val statusEffects = fixture.buildStatusEffects(bleed, concuss)
        val combat = fixture.buildCombat(
            rng = Random(1),
            petSystem = pets,
            statusEffects = statusEffects,
        )
        return Fixture(fixture, combat, pets)
    }

    private data class Fixture(
        val base: CombatTestFixture,
        val combat: CombatSystem,
        val pets: PetSystem,
    )

    private suspend fun setupPlayerInCombat(
        fixture: Fixture,
        petTemplate: String,
        mobHp: Int = 100,
    ): Triple<SessionId, MobState, MobState> {
        val sid = SessionId(7L)
        fixture.base.players.loginOrFail(sid, "Ranger")
        val player = fixture.base.players.get(sid)!!
        val pet = fixture.pets.summon(sid, petTemplate, player.roomId, ownerStats)!!
        val target = MobState(
            MobId("demo:goblin"),
            "a goblin",
            fixture.base.roomId,
            hp = mobHp,
            maxHp = mobHp,
        )
        fixture.base.mobs.upsert(target)
        fixture.combat.startCombat(sid, "goblin")
        fixture.base.outbound.drainAll()
        return Triple(sid, pet, target)
    }

    @Test
    fun `triggerPetSkill applies damage and status effect`() = runTest {
        val f = newFixture()
        val (sid, pet, target) = setupPlayerInCombat(f, "wolf_companion")
        val startHp = target.hp

        val result = f.combat.triggerPetSkill(sid, "bite")
        assertEquals(CombatSystem.PetSkillResult.Ok, result)

        assertTrue(target.hp < startHp, "Expected target to take bite damage")
        // Damage range is 4-4; armor 0 -> 4 damage
        assertEquals(startHp - 4, target.hp)

        val events = f.base.outbound.drainAll().filterIsInstance<dev.ambon.engine.events.OutboundEvent.SendText>()
        assertTrue(events.any { it.text.contains("bites") }, "Expected bite message. got=${events.map { it.text }}")
        assertTrue(events.any { it.sessionId == sid })
        // Suppress unused-var lint: pet is part of fixture return, asserts above use indirect refs.
        assertEquals(sid, pet.ownerSessionId)
    }

    @Test
    fun `cooldown prevents immediate re-cast`() = runTest {
        val f = newFixture()
        val (sid, _, _) = setupPlayerInCombat(f, "wolf_companion")

        assertEquals(CombatSystem.PetSkillResult.Ok, f.combat.triggerPetSkill(sid, "bite"))

        val second = f.combat.triggerPetSkill(sid, "bite")
        assertTrue(second is CombatSystem.PetSkillResult.Error)
        assertEquals("ON_COOLDOWN", (second as CombatSystem.PetSkillResult.Error).code)
    }

    @Test
    fun `cooldown clears after duration`() = runTest {
        val f = newFixture()
        val (sid, _, _) = setupPlayerInCombat(f, "wolf_companion")

        f.combat.triggerPetSkill(sid, "bite")
        f.base.clock.advance(6_000L)
        val result = f.combat.triggerPetSkill(sid, "bite")
        assertEquals(CombatSystem.PetSkillResult.Ok, result)
    }

    @Test
    fun `trigger fails when not in combat`() = runTest {
        val f = newFixture()
        val sid = SessionId(7L)
        f.base.players.loginOrFail(sid, "Ranger")
        val player = f.base.players.get(sid)!!
        f.pets.summon(sid, "wolf_companion", player.roomId, ownerStats)

        val result = f.combat.triggerPetSkill(sid, "bite")
        assertTrue(result is CombatSystem.PetSkillResult.Error)
        assertEquals("NOT_IN_COMBAT", (result as CombatSystem.PetSkillResult.Error).code)
    }

    @Test
    fun `trigger fails when no active pet`() = runTest {
        val f = newFixture()
        val sid = SessionId(7L)
        f.base.players.loginOrFail(sid, "Ranger")

        val result = f.combat.triggerPetSkill(sid, "bite")
        assertTrue(result is CombatSystem.PetSkillResult.Error)
        assertEquals("NO_ACTIVE_PET", (result as CombatSystem.PetSkillResult.Error).code)
    }

    @Test
    fun `trigger fails for unknown skill name`() = runTest {
        val f = newFixture()
        val (sid, _, _) = setupPlayerInCombat(f, "wolf_companion")

        val result = f.combat.triggerPetSkill(sid, "nonsense")
        assertTrue(result is CombatSystem.PetSkillResult.Error)
        assertEquals("UNKNOWN_SKILL", (result as CombatSystem.PetSkillResult.Error).code)
    }

    @Test
    fun `manual trigger records grace timestamp blocking auto-cast`() = runTest {
        val f = newFixture()
        val (sid, _, _) = setupPlayerInCombat(f, "wolf_companion")

        // Manually trigger bite — records timestamp
        f.combat.triggerPetSkill(sid, "bite")
        assertTrue(f.pets.isManualGraceActive(sid, f.base.clock.millis()))

        // Within grace window — still active
        f.base.clock.advance(7_999L)
        assertTrue(f.pets.isManualGraceActive(sid, f.base.clock.millis()))

        // Past grace window — no longer active
        f.base.clock.advance(2L)
        assertTrue(!f.pets.isManualGraceActive(sid, f.base.clock.millis()))
    }

    @Test
    fun `auto-cast fires a pet skill during pet attack phase`() = runTest {
        val f = newFixture()
        val (_, _, target) = setupPlayerInCombat(f, "wolf_companion")
        val startHp = target.hp

        // Tick once — pet attack phase should pick a skill since no manual cast has happened
        f.base.tickCombat(f.combat)

        val finalHp = f.base.mobs.get(target.id)!!.hp
        // Either bite (4 dmg) or pounce (6 dmg) — both > pet melee max of 2
        val damage = startHp - finalHp
        assertTrue(damage >= 4, "Expected skill-level damage (>=4), got $damage")
    }

    @Test
    fun `auto-cast suppressed during manual grace window`() = runTest {
        val f = newFixture()
        val (sid, _, _) = setupPlayerInCombat(f, "wolf_companion")

        f.combat.triggerPetSkill(sid, "pounce") // 6 dmg, cd 12s
        f.base.outbound.drainAll()

        // Advance 1s — within grace; auto-cast should be suppressed.
        // Bite is off cooldown (only pounce was used), but auto-cast must NOT fire.
        // Pet should fall through to plain melee text ("hits ... for ... damage"), not a skill message.
        f.base.tickCombat(f.combat)
        val texts = f.base.outbound.drainAll()
            .filterIsInstance<dev.ambon.engine.events.OutboundEvent.SendText>()
            .map { it.text }
        assertTrue(
            texts.none { it.contains("bites") || it.contains("pounces") },
            "Expected no skill message during manual grace, got=$texts",
        )
        assertTrue(
            texts.any { it.contains("a wolf companion hits") },
            "Expected pet melee hit during manual grace, got=$texts",
        )
    }

    @Test
    fun `threatBonus skill adds threat without damage`() = runTest {
        val f = newFixture()
        val (sid, pet, target) = setupPlayerInCombat(f, "bear_guardian")
        val startHp = target.hp

        val result = f.combat.triggerPetSkill(sid, "roar")
        assertEquals(CombatSystem.PetSkillResult.Ok, result)
        assertEquals(startHp, f.base.mobs.get(target.id)!!.hp, "Roar should deal no damage")

        // Bear has threatMultiplier > 0 so the pet has a synthetic SID; that SID should have threat now.
        val petSid = f.pets.getPetSessionId(pet.id)
        assertNotNull(petSid)
        assertTrue(f.combat.threatTable.getThreat(target.id, petSid!!) >= 100.0)
    }

    @Test
    fun `findSkill matches by id and displayName prefix`() = runTest {
        val f = newFixture()
        val sid = SessionId(7L)
        f.base.players.loginOrFail(sid, "Ranger")
        val player = f.base.players.get(sid)!!
        val pet = f.pets.summon(sid, "wolf_companion", player.roomId, ownerStats)!!

        assertNotNull(f.pets.findSkill(pet, "bite"))
        assertNotNull(f.pets.findSkill(pet, "BITE"))
        assertNotNull(f.pets.findSkill(pet, "po")) // prefix of "Pounce"
        assertNull(f.pets.findSkill(pet, "fireball"))
    }
}
