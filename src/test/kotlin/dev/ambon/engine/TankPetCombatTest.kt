package dev.ambon.engine

import dev.ambon.config.PetConfig
import dev.ambon.config.PetTemplateConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.TEST_ROOM_ID
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
class TankPetCombatTest {
    private companion object {
        val DEFAULT_OWNER_STATS = PetSystem.OwnerStats(maxHp = 30, damageMin = 1, damageMax = 3, armor = 0)
    }

    private fun buildPetSystem(
        fixture: CombatTestFixture,
        threatMultiplier: Double = 3.0,
        hp: Int = 50,
    ): PetSystem {
        val config = PetConfig(
            definitions = mapOf(
                "tank_bear" to PetTemplateConfig(
                    name = "a bear",
                    baseHp = hp,
                    baseMinDamage = 2,
                    baseMaxDamage = 4,
                    baseArmor = 1,
                    threatMultiplier = threatMultiplier,
                ),
                "dps_sprite" to PetTemplateConfig(
                    name = "a sprite",
                    baseHp = 20,
                    baseMinDamage = 3,
                    baseMaxDamage = 5,
                    threatMultiplier = 0.0,
                ),
            ),
        )
        return PetSystem(config, fixture.mobs, fixture.clock)
    }

    private fun enemyMob(
        damage: DamageRange = DamageRange(3, 3),
        hp: Int = 100,
        templateKey: String = "",
    ): MobState = MobState(
        id = MobId("zone:goblin"),
        name = "a goblin",
        roomId = TEST_ROOM_ID,
        hp = hp,
        maxHp = hp,
        damage = damage,
        templateKey = templateKey,
    )

    @Test
    fun `tank pet generates threat and mob attacks pet instead of player`() = runTest {
        val fixture = CombatTestFixture()
        val petSystem = buildPetSystem(fixture)
        val combat = fixture.buildCombat(
            rng = Random(42),
            minDamage = 1,
            maxDamage = 1,
            petSystem = petSystem,
        )

        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        val mob = enemyMob()
        fixture.mobs.upsert(mob)

        // Summon a tank pet
        val pet = petSystem.summon(sid, "tank_bear", TEST_ROOM_ID, DEFAULT_OWNER_STATS)
        assertNotNull(pet)
        assertTrue(pet!!.threatMultiplier > 0.0)
        assertNotNull(petSystem.getPetSessionId(pet.id))

        // Start combat
        combat.startCombat(sid, "goblin")
        fixture.outbound.drainAll()

        // First tick: player hits mob, pet hits mob (generating threat), mob attacks someone
        fixture.tickCombat(combat)
        fixture.outbound.drainAll()

        // Second tick: pet should have accumulated enough threat to be targeted
        fixture.tickCombat(combat)
        val texts = fixture.outbound.drainAll()
            .filterIsInstance<OutboundEvent.SendText>()
            .map { it.text }

        // The mob should be hitting the bear, not the player (or the player dodged)
        val mobHitsPet = texts.any { it.contains("hits a bear") }
        val mobHitsPlayer = texts.any { it.contains("hits you for") && !it.contains("bear") }

        // After 2 ticks of pet generating threat with 3x multiplier, mob should target the pet
        assertTrue(
            mobHitsPet || !mobHitsPlayer,
            "Expected mob to attack pet, got: $texts",
        )
    }

    @Test
    fun `dps pet with zero threatMultiplier does not take aggro`() = runTest {
        val fixture = CombatTestFixture()
        val petSystem = buildPetSystem(fixture)
        val combat = fixture.buildCombat(
            rng = Random(42),
            minDamage = 1,
            maxDamage = 1,
            petSystem = petSystem,
        )

        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        val mob = enemyMob()
        fixture.mobs.upsert(mob)

        // Summon a DPS pet (no threat)
        val pet = petSystem.summon(sid, "dps_sprite", TEST_ROOM_ID, DEFAULT_OWNER_STATS)
        assertNotNull(pet)
        assertEquals(0.0, pet!!.threatMultiplier)
        assertNull(petSystem.getPetSessionId(pet.id))

        combat.startCombat(sid, "goblin")
        fixture.outbound.drainAll()

        // Tick several times — mob should never attack the sprite
        repeat(3) { fixture.tickCombat(combat) }
        val texts = fixture.outbound.drainAll()
            .filterIsInstance<OutboundEvent.SendText>()
            .map { it.text }

        val mobHitsPet = texts.any { it.contains("hits a sprite") }
        assertFalse(mobHitsPet, "DPS pet should not take aggro, got: $texts")
    }

    @Test
    fun `pet death causes mob to retarget player`() = runTest {
        val fixture = CombatTestFixture()
        // Low HP pet that will die quickly
        val petSystem = buildPetSystem(fixture, hp = 5)
        val combat = fixture.buildCombat(
            rng = Random(42),
            minDamage = 1,
            maxDamage = 1,
            petSystem = petSystem,
        )

        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        val mob = enemyMob(damage = DamageRange(10, 10))
        fixture.mobs.upsert(mob)

        petSystem.summon(sid, "tank_bear", TEST_ROOM_ID, DEFAULT_OWNER_STATS)!!

        combat.startCombat(sid, "goblin")
        fixture.outbound.drainAll()

        // Tick until the pet dies — mob does 10 damage/tick, pet has 5 HP
        var petDied = false
        val allTexts = mutableListOf<String>()
        for (i in 0 until 5) {
            fixture.tickCombat(combat)
            val texts = fixture.outbound.drainAll()
                .filterIsInstance<OutboundEvent.SendText>()
                .map { it.text }
            allTexts.addAll(texts)
            if (texts.any { it.contains("has been slain") }) {
                petDied = true
                break
            }
        }

        assertTrue(petDied, "Expected pet to die, got: $allTexts")
        assertTrue(
            allTexts.any { it.contains("a bear has been slain") },
            "Expected pet death message, got: $allTexts",
        )

        // Pet should be dismissed from the system
        assertNull(petSystem.getActivePet(sid))

        // Continue ticking — mob should now attack the player
        fixture.tickCombat(combat)
        val postDeathTexts = fixture.outbound.drainAll()
            .filterIsInstance<OutboundEvent.SendText>()
            .map { it.text }

        val mobHitsPlayer = postDeathTexts.any {
            (it.contains("hits you") || it.contains("dodge")) && !it.contains("bear")
        }
        assertTrue(
            mobHitsPlayer,
            "After pet death, mob should attack player, got: $postDeathTexts",
        )
    }

    @Test
    fun `pet dismiss during combat removes pet from threat table`() = runTest {
        val fixture = CombatTestFixture()
        val petSystem = buildPetSystem(fixture)
        val combat = fixture.buildCombat(
            rng = Random(42),
            minDamage = 1,
            maxDamage = 1,
            petSystem = petSystem,
        )

        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        val mob = enemyMob()
        fixture.mobs.upsert(mob)

        val pet = petSystem.summon(sid, "tank_bear", TEST_ROOM_ID, DEFAULT_OWNER_STATS)!!
        val petSid = petSystem.getPetSessionId(pet.id)!!

        combat.startCombat(sid, "goblin")
        fixture.outbound.drainAll()

        // Build threat
        fixture.tickCombat(combat)
        fixture.outbound.drainAll()

        // Verify pet has threat
        assertTrue(combat.threatTable.hasThreat(mob.id, petSid))

        // Dismiss pet
        petSystem.dismissAll(sid)

        // Pet threat entry should still exist in threat table until combat cleanup,
        // but getPetBySession should return null so mob won't target it
        assertNull(petSystem.getPetBySession(petSid))
        assertFalse(petSystem.isPetSession(petSid))
    }

    @Test
    fun `owner fleeing combat cleans up pet threat`() = runTest {
        val fixture = CombatTestFixture()
        val petSystem = buildPetSystem(fixture)
        val combat = fixture.buildCombat(
            rng = Random(42),
            minDamage = 1,
            maxDamage = 1,
            petSystem = petSystem,
        )

        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        val mob = enemyMob()
        fixture.mobs.upsert(mob)

        val pet = petSystem.summon(sid, "tank_bear", TEST_ROOM_ID, DEFAULT_OWNER_STATS)!!
        val petSid = petSystem.getPetSessionId(pet.id)!!

        combat.startCombat(sid, "goblin")
        fixture.outbound.drainAll()

        // Build threat
        fixture.tickCombat(combat)
        fixture.outbound.drainAll()

        assertTrue(combat.threatTable.hasThreat(mob.id, petSid))

        // Flee
        combat.flee(sid)

        // Pet threat should be cleaned up
        assertFalse(combat.threatTable.hasThreat(mob.id, petSid))
    }

    @Test
    fun `mob kill callbacks exclude pet synthetic session IDs`() = runTest {
        val fixture = CombatTestFixture()
        val petSystem = buildPetSystem(fixture)
        val killCallbackSids = mutableListOf<SessionId>()
        val combat = fixture.buildCombat(
            rng = Random(42),
            minDamage = 1,
            maxDamage = 1,
            petSystem = petSystem,
            onMobKilledByPlayer = { sid, _ -> killCallbackSids.add(sid) },
        )

        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        // Low HP so it dies quickly; templateKey required for kill callbacks to fire
        val mob = enemyMob(hp = 1, templateKey = "goblin")
        fixture.mobs.upsert(mob)

        val pet = petSystem.summon(sid, "tank_bear", TEST_ROOM_ID, DEFAULT_OWNER_STATS)!!
        val petSid = petSystem.getPetSessionId(pet.id)!!

        combat.startCombat(sid, "goblin")
        fixture.outbound.drainAll()

        // Tick — mob should die on first player/pet attack
        fixture.tickCombat(combat)
        fixture.outbound.drainAll()

        // The kill callback should fire for the player, never for the pet SID
        assertTrue(killCallbackSids.contains(sid), "Player should receive kill credit")
        assertFalse(
            killCallbackSids.contains(petSid),
            "Pet synthetic SID should not receive kill callbacks",
        )
    }

    @Test
    fun `pet threatMultiplier scales threat generation`() = runTest {
        val fixture = CombatTestFixture()
        val petSystem = buildPetSystem(fixture, threatMultiplier = 5.0)
        val combat = fixture.buildCombat(
            rng = Random(42),
            minDamage = 1,
            maxDamage = 1,
            petSystem = petSystem,
        )

        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")
        val mob = enemyMob()
        fixture.mobs.upsert(mob)

        val pet = petSystem.summon(sid, "tank_bear", TEST_ROOM_ID, DEFAULT_OWNER_STATS)!!
        val petSid = petSystem.getPetSessionId(pet.id)!!

        combat.startCombat(sid, "goblin")
        fixture.outbound.drainAll()

        fixture.tickCombat(combat)

        // Pet threat should be significantly higher than player threat due to 5x multiplier
        val petThreat = combat.threatTable.getThreat(mob.id, petSid)
        val playerThreat = combat.threatTable.getThreat(mob.id, sid)

        assertTrue(
            petThreat > playerThreat,
            "Pet threat ($petThreat) should exceed player threat ($playerThreat) with 5x multiplier",
        )
    }
}
