package dev.ambon.engine

import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MobSystemTest {
    private val roomA = RoomId("zone:roomA")
    private val roomB = RoomId("zone:roomB")

    @Test
    fun `tick always returns 0 - movement is driven by BehaviorTreeSystem`() =
        runTest {
            val system = MobSystem()
            assertEquals(0, system.tick())
            assertEquals(0, system.tick())
        }

    @Test
    fun `mob spawn populates room with correct stats`() {
        val mobs = MobRegistry()
        val mob = MobState(
            id = MobId("zone:goblin"),
            name = "a goblin",
            roomId = roomA,
            hp = 30,
            maxHp = 30,
            damage = DamageRange(3, 6),
            armor = 2,
            xpReward = 50L,
        )
        mobs.upsert(mob)

        val found = mobs.mobsInRoom(roomA)
        assertEquals(1, found.size)
        assertEquals("a goblin", found[0].name)
        assertEquals(30, found[0].hp)
        assertEquals(30, found[0].maxHp)
        assertEquals(50L, found[0].xpReward)
    }

    @Test
    fun `mob death removes from room`() {
        val mobs = MobRegistry()
        val mobId = MobId("zone:rat")
        mobs.upsert(MobState(mobId, "a rat", roomA, hp = 10, maxHp = 10))

        assertEquals(1, mobs.mobsInRoom(roomA).size)

        // Simulate death: remove from registry
        mobs.remove(mobId)

        assertTrue(mobs.mobsInRoom(roomA).isEmpty(), "Room should be empty after mob death")
        assertNull(mobs.get(mobId), "Mob should not exist in registry")
    }

    @Test
    fun `mob respawn via scheduler restores mob after delay`() = runTest {
        val clock = MutableClock(0L)
        val scheduler = Scheduler(clock)
        val mobs = MobRegistry()
        val mobSystem = MobSystem()
        val mobId = MobId("zone:rat")

        // Spawn initial mob
        mobs.upsert(MobState(mobId, "a rat", roomA, hp = 10, maxHp = 10))
        mobSystem.onMobSpawned(mobId)

        // Kill the mob
        mobs.remove(mobId)
        mobSystem.onMobRemoved(mobId)
        assertNull(mobs.get(mobId))

        // Schedule respawn (60 seconds)
        val respawnMs = 60_000L
        scheduler.scheduleIn(respawnMs) {
            if (mobs.get(mobId) == null) {
                mobs.upsert(MobState(mobId, "a rat", roomA, hp = 10, maxHp = 10))
                mobSystem.onMobSpawned(mobId)
            }
        }

        // Advance time less than respawn — still dead
        clock.advance(30_000L)
        scheduler.runDue()
        assertNull(mobs.get(mobId), "Mob should still be dead before respawn time")

        // Advance past respawn time
        clock.advance(31_000L)
        scheduler.runDue()
        assertNotNull(mobs.get(mobId), "Mob should have respawned")
        assertEquals("a rat", mobs.get(mobId)!!.name)
        assertEquals(10, mobs.get(mobId)!!.hp, "Respawned mob should have full HP")
    }

    @Test
    fun `multiple mobs can exist in same room`() {
        val mobs = MobRegistry()
        mobs.upsert(MobState(MobId("zone:rat_1"), "a rat", roomA, hp = 10, maxHp = 10))
        mobs.upsert(MobState(MobId("zone:rat_2"), "a rat", roomA, hp = 10, maxHp = 10))
        mobs.upsert(MobState(MobId("zone:spider"), "a spider", roomA, hp = 15, maxHp = 15))

        assertEquals(3, mobs.mobsInRoom(roomA).size)

        // Remove one mob
        mobs.remove(MobId("zone:rat_1"))
        assertEquals(2, mobs.mobsInRoom(roomA).size)
    }

    @Test
    fun `mob movement between rooms updates room membership`() {
        val mobs = MobRegistry()
        val mobId = MobId("zone:wolf")
        mobs.upsert(MobState(mobId, "a wolf", roomA, hp = 20, maxHp = 20))

        assertEquals(1, mobs.mobsInRoom(roomA).size)
        assertTrue(mobs.mobsInRoom(roomB).isEmpty())

        mobs.moveTo(mobId, roomB)

        assertTrue(mobs.mobsInRoom(roomA).isEmpty(), "Wolf should no longer be in room A")
        assertEquals(1, mobs.mobsInRoom(roomB).size, "Wolf should be in room B")
        assertEquals(roomB, mobs.get(mobId)!!.roomId)
    }

    @Test
    fun `aggressive mob flag is tracked`() {
        val mobs = MobRegistry()
        val passiveMob = MobState(
            id = MobId("zone:deer"),
            name = "a deer",
            roomId = roomA,
            hp = 10,
            maxHp = 10,
            aggressive = false,
        )
        val aggroMob = MobState(
            id = MobId("zone:wolf"),
            name = "a wolf",
            roomId = roomA,
            hp = 20,
            maxHp = 20,
            aggressive = true,
        )
        mobs.upsert(passiveMob)
        mobs.upsert(aggroMob)

        val roomMobs = mobs.mobsInRoom(roomA)
        val aggressiveMobs = roomMobs.filter { it.aggressive }
        val passiveMobs = roomMobs.filter { !it.aggressive }

        assertEquals(1, aggressiveMobs.size, "Should be 1 aggressive mob")
        assertEquals("a wolf", aggressiveMobs[0].name)
        assertEquals(1, passiveMobs.size, "Should be 1 passive mob")
        assertEquals("a deer", passiveMobs[0].name)
    }

    @Test
    fun `mob not respawned if already present in registry`() = runTest {
        val clock = MutableClock(0L)
        val scheduler = Scheduler(clock)
        val mobs = MobRegistry()
        val mobId = MobId("zone:rat")

        // Mob is alive
        mobs.upsert(MobState(mobId, "a rat", roomA, hp = 10, maxHp = 10))

        // Schedule a respawn that checks if mob already exists
        scheduler.scheduleIn(1_000L) {
            if (mobs.get(mobId) == null) {
                mobs.upsert(MobState(mobId, "a rat", roomA, hp = 10, maxHp = 10))
            }
        }

        clock.advance(1_500L)
        scheduler.runDue()

        // Should still be just 1 mob — the scheduler skip should have prevented duplicate
        assertEquals(1, mobs.all().size, "Should not duplicate mob")
    }
}
