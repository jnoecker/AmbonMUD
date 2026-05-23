package dev.ambon.engine

import dev.ambon.config.PetConfig
import dev.ambon.config.PetSpellConfig
import dev.ambon.config.PetTemplateConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PetSystemTest {
    private val clock = MutableClock(1000L)
    private val sid1 = SessionId(1L)
    private val sid2 = SessionId(2L)
    private val room1 = RoomId("test:room1")
    private val room2 = RoomId("test:room2")

    // Stand-in for a low-level owner — floors should dominate.
    private val lowLevelOwner = PetSystem.OwnerStats(maxHp = 30, damageMin = 1, damageMax = 3, armor = 0)

    // Stand-in for a mid-level owner — ratios should dominate.
    private val midLevelOwner = PetSystem.OwnerStats(maxHp = 200, damageMin = 20, damageMax = 40, armor = 10)

    // Stand-in for a max-geared owner — global caps should bite.
    private val highLevelOwner = PetSystem.OwnerStats(maxHp = 1_000, damageMin = 100, damageMax = 200, armor = 50)

    private val config = PetConfig(
        definitions = mapOf(
            "fire_familiar" to PetTemplateConfig(
                name = "a fire familiar",
                description = "A small elemental of living flame.",
                hpRatio = 0.5,
                damageRatio = 0.5,
                armorRatio = 0.4,
                baseHp = 20,
                baseMinDamage = 2,
                baseMaxDamage = 5,
                baseArmor = 1,
                spells = mapOf(
                    "scorch" to PetSpellConfig(
                        displayName = "Scorch",
                        damageRatio = 2.0,
                    ),
                    "mend" to PetSpellConfig(
                        displayName = "Mend",
                        healRatio = 0.1,
                    ),
                ),
            ),
            "stone_golem" to PetTemplateConfig(
                name = "a stone golem",
                description = "A hulking construct of animate stone.",
                hpRatio = 0.9,
                damageRatio = 0.4,
                armorRatio = 0.8,
                baseHp = 50,
                baseMinDamage = 4,
                baseMaxDamage = 8,
                baseArmor = 5,
            ),
            // Intentionally over-tuned to verify global caps.
            "overtuned" to PetTemplateConfig(
                name = "an overtuned pet",
                hpRatio = 2.0,
                damageRatio = 2.0,
                armorRatio = 2.0,
                baseHp = 1,
                baseMinDamage = 1,
                baseMaxDamage = 1,
                baseArmor = 0,
            ),
        ),
    )

    private lateinit var mobs: MobRegistry
    private lateinit var pets: PetSystem

    @BeforeEach
    fun setUp() {
        mobs = MobRegistry()
        pets = PetSystem(config = config, mobs = mobs, clock = clock)
    }

    @Nested
    inner class Summoning {
        @Test
        fun `summon creates a pet mob in the room`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, lowLevelOwner)
            assertNotNull(pet)
            assertEquals("a fire familiar", pet!!.name)
            assertEquals(room1, pet.roomId)
            assertEquals(sid1, pet.ownerSessionId)
            assertTrue(pet.isPet)

            val mobsInRoom = mobs.mobsInRoom(room1)
            assertEquals(1, mobsInRoom.size)
            assertEquals(pet.id, mobsInRoom[0].id)
        }

        @Test
        fun `summon unknown template returns null`() {
            assertNull(pets.summon(sid1, "nonexistent", room1, lowLevelOwner))
        }

        @Test
        fun `summoning a second pet dismisses the first`() {
            val pet1 = pets.summon(sid1, "fire_familiar", room1, lowLevelOwner)!!
            val pet2 = pets.summon(sid1, "stone_golem", room1, lowLevelOwner)!!

            assertNull(mobs.get(pet1.id))
            assertNotNull(mobs.get(pet2.id))
            assertEquals("a stone golem", pets.getActivePet(sid1)?.name)
        }

        @Test
        fun `low-level owner falls back to template floors`() {
            // Owner stats are smaller than the floor — pet uses baseHp / baseMinDamage / baseMaxDamage / baseArmor.
            val pet = pets.summon(sid1, "fire_familiar", room1, lowLevelOwner)!!
            assertEquals(20, pet.maxHp)
            assertEquals(2, pet.damage.min)
            assertEquals(5, pet.damage.max)
            assertEquals(1, pet.armor)
        }

        @Test
        fun `mid-level owner scales pet via ratios`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, midLevelOwner)!!
            // 200 * 0.5 = 100 HP, 20..40 * 0.5 = 10..20 damage, 10 * 0.4 = 4 armor.
            assertEquals(100, pet.maxHp)
            assertEquals(10, pet.damage.min)
            assertEquals(20, pet.damage.max)
            assertEquals(4, pet.armor)
        }

        @Test
        fun `global ratio caps prevent gear-stacked pets from out-scaling owner`() {
            val pet = pets.summon(sid1, "overtuned", room1, highLevelOwner)!!
            // PetConfig defaults: maxHpRatio=1.0, maxDamageRatio=0.8, maxArmorRatio=1.0.
            assertEquals(1_000, pet.maxHp) // capped at owner's full HP
            assertEquals(80, pet.damage.min) // capped at owner damage * 0.8
            assertEquals(160, pet.damage.max)
            assertEquals(50, pet.armor) // capped at owner armor
        }

        @Test
        fun `spell damageRatio anchors to pet's scaled melee swing`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, midLevelOwner)!!
            val scorch = pet.spells.first { it.id == "scorch" }
            // Pet melee was 10..20; scorch.damageRatio=2.0 → 20..40.
            val scorchDamage = scorch.damage
            assertNotNull(scorchDamage)
            assertEquals(20, scorchDamage!!.min)
            assertEquals(40, scorchDamage.max)
        }

        @Test
        fun `spell healRatio anchors to owner maxHp`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, midLevelOwner)!!
            val mend = pet.spells.first { it.id == "mend" }
            // Owner maxHp 200 * healRatio 0.1 = 20.
            assertEquals(20, mend.healMin)
            assertEquals(20, mend.healMax)
        }

        @Test
        fun `different players have independent pets`() {
            pets.summon(sid1, "fire_familiar", room1, lowLevelOwner)
            pets.summon(sid2, "stone_golem", room1, lowLevelOwner)

            assertEquals("a fire familiar", pets.getActivePet(sid1)?.name)
            assertEquals("a stone golem", pets.getActivePet(sid2)?.name)
        }

        @Test
        fun `timed pet exists before duration expires`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, lowLevelOwner, durationMs = 5000L)
            assertNotNull(pet)

            clock.advance(4999L)
            val expired = pets.tick()
            assertTrue(expired.isEmpty())
            assertNotNull(pets.getActivePet(sid1))
        }

        @Test
        fun `timed pet expires after duration`() {
            pets.summon(sid1, "fire_familiar", room1, lowLevelOwner, durationMs = 5000L)

            clock.advance(5000L)
            val expired = pets.tick()
            assertEquals(1, expired.size)
            assertEquals(sid1, expired[0].ownerSessionId)
            assertEquals("a fire familiar", expired[0].petName)
            assertNull(pets.getActivePet(sid1))
        }

        @Test
        fun `permanent pet does not expire`() {
            pets.summon(sid1, "fire_familiar", room1, lowLevelOwner, durationMs = 0L)

            clock.advance(999_999L)
            val expired = pets.tick()
            assertTrue(expired.isEmpty())
            assertNotNull(pets.getActivePet(sid1))
        }

        @Test
        fun `dismissing a timed pet clears its expiry`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, lowLevelOwner, durationMs = 5000L)!!
            pets.dismissAll(sid1)

            clock.advance(6000L)
            val expired = pets.tick()
            assertTrue(expired.isEmpty())
            assertNull(mobs.get(pet.id))
        }
    }

    @Nested
    inner class Following {
        @Test
        fun `pet follows owner to new room`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, lowLevelOwner)!!

            pets.followOwner(sid1, room2)

            val movedPet = mobs.get(pet.id)
            assertNotNull(movedPet)
            assertEquals(room2, movedPet!!.roomId)
        }
    }

    @Nested
    inner class Dismissal {
        @Test
        fun `dismiss removes pet from mob registry`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, lowLevelOwner)!!
            pets.dismissAll(sid1)

            assertNull(mobs.get(pet.id))
            assertNull(pets.getActivePet(sid1))
        }

        @Test
        fun `onOwnerDisconnect dismisses pets`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, lowLevelOwner)!!
            pets.onOwnerDisconnect(sid1)

            assertNull(mobs.get(pet.id))
        }
    }
}
