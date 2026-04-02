package dev.ambon.engine

import dev.ambon.config.PetConfig
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

    private val config = PetConfig(
        definitions = mapOf(
            "fire_familiar" to PetTemplateConfig(
                name = "a fire familiar",
                description = "A small elemental of living flame.",
                hp = 20,
                minDamage = 2,
                maxDamage = 5,
                armor = 1,
            ),
            "stone_golem" to PetTemplateConfig(
                name = "a stone golem",
                description = "A hulking construct of animate stone.",
                hp = 50,
                minDamage = 4,
                maxDamage = 8,
                armor = 5,
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
            val pet = pets.summon(sid1, "fire_familiar", room1, 1)
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
            assertNull(pets.summon(sid1, "nonexistent", room1, 1))
        }

        @Test
        fun `summoning a second pet dismisses the first`() {
            val pet1 = pets.summon(sid1, "fire_familiar", room1, 1)!!
            val pet2 = pets.summon(sid1, "stone_golem", room1, 1)!!

            assertNull(mobs.get(pet1.id))
            assertNotNull(mobs.get(pet2.id))
            assertEquals("a stone golem", pets.getActivePet(sid1)?.name)
        }

        @Test
        fun `pet stats scale with owner level`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, 10)!!
            // Level 10: scale = 1 + (10-1)*0.1 = 1.9
            assertEquals((20 * 1.9).toInt(), pet.maxHp)
            assertTrue(pet.damage.min > 2) // scaled up from base
        }

        @Test
        fun `different players have independent pets`() {
            pets.summon(sid1, "fire_familiar", room1, 1)
            pets.summon(sid2, "stone_golem", room1, 1)

            assertEquals("a fire familiar", pets.getActivePet(sid1)?.name)
            assertEquals("a stone golem", pets.getActivePet(sid2)?.name)
        }
    }

    @Nested
    inner class Following {
        @Test
        fun `pet follows owner to new room`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, 1)!!

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
            val pet = pets.summon(sid1, "fire_familiar", room1, 1)!!
            pets.dismissAll(sid1)

            assertNull(mobs.get(pet.id))
            assertNull(pets.getActivePet(sid1))
        }

        @Test
        fun `onOwnerDisconnect dismisses pets`() {
            val pet = pets.summon(sid1, "fire_familiar", room1, 1)!!
            pets.onOwnerDisconnect(sid1)

            assertNull(mobs.get(pet.id))
        }
    }
}
