package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MobRespawnTest {
    private val roomId = RoomId("zone:room")
    private val mobId = MobId("zone:rat")

    /**
     * Builds the same onMobRemoved lambda that GameEngine wires up, using the provided
     * components. This lets us test the scheduling logic in isolation.
     */
    private fun buildOnMobRemoved(
        world: World,
        mobs: MobRegistry,
        mobSystem: MobSystem,
        scheduler: Scheduler,
        players: PlayerRegistry,
        outbound: LocalOutboundBus,
    ): suspend (MobId, RoomId) -> Unit =
        { id, _ ->
            mobSystem.onMobRemoved(id)
            val spawn = world.mobSpawns.find { it.id == id }
            val respawnMs = spawn?.respawnSeconds?.let { it * 1_000L }
            if (spawn != null && respawnMs != null) {
                scheduler.scheduleIn(respawnMs) {
                    if (mobs.get(spawn.id) != null) return@scheduleIn
                    if (world.rooms[spawn.roomId] == null) return@scheduleIn
                    mobs.upsert(
                        MobState(
                            id = spawn.id,
                            name = spawn.name,
                            roomId = spawn.roomId,
                            hp = spawn.maxHp,
                            maxHp = spawn.maxHp,
                            minDamage = spawn.minDamage,
                            maxDamage = spawn.maxDamage,
                            armor = spawn.armor,
                            xpReward = spawn.xpReward,
                            drops = spawn.drops,
                        ),
                    )
                    mobSystem.onMobSpawned(spawn.id)
                    for (p in players.playersInRoom(spawn.roomId)) {
                        outbound.send(OutboundEvent.SendText(p.sessionId, "${spawn.name} appears."))
                    }
                }
            }
        }

    @Test
    fun `mob with respawnSeconds respawns in origin room after delay`() =
        runTest {
            val room = Room(roomId, "A Room", "A room.", emptyMap())
            val spawn = MobSpawn(id = mobId, name = "a rat", roomId = roomId, respawnSeconds = 60L)
            val world = World(mapOf(roomId to room), roomId, mobSpawns = listOf(spawn))

            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val clock = MutableClock(0L)
            val scheduler = Scheduler(clock)
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), ItemRegistry())
            val mobSystem = MobSystem()

            val onMobRemoved = buildOnMobRemoved(world, mobs, mobSystem, scheduler, players, outbound)

            mobs.upsert(MobState(mobId, "a rat", roomId))
            mobs.remove(mobId)
            onMobRemoved(mobId, roomId)

            assertNull(mobs.get(mobId), "Mob should be gone immediately after removal")
            assertEquals(1, scheduler.size(), "Respawn should be scheduled")

            // Before delay elapses — mob still absent
            scheduler.runDue()
            assertNull(mobs.get(mobId), "Mob should not respawn before delay")

            // After delay elapses — mob respawns
            clock.advance(60_000L)
            scheduler.runDue()

            val respawned = mobs.get(mobId)
            assertNotNull(respawned, "Mob should have respawned")
            assertEquals(roomId, respawned!!.roomId)
            assertEquals(spawn.maxHp, respawned.hp)
        }

    @Test
    fun `respawn is skipped when mob is already in registry at fire time`() =
        runTest {
            val room = Room(roomId, "A Room", "A room.", emptyMap())
            val spawn = MobSpawn(id = mobId, name = "a rat", roomId = roomId, respawnSeconds = 60L)
            val world = World(mapOf(roomId to room), roomId, mobSpawns = listOf(spawn))

            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val clock = MutableClock(0L)
            val scheduler = Scheduler(clock)
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), ItemRegistry())
            val mobSystem = MobSystem()

            val onMobRemoved = buildOnMobRemoved(world, mobs, mobSystem, scheduler, players, outbound)

            mobs.upsert(MobState(mobId, "a rat", roomId))
            mobs.remove(mobId)
            onMobRemoved(mobId, roomId)

            // Simulate zone reset re-adding the mob before the scheduler fires
            mobs.upsert(MobState(mobId, "a rat", roomId, hp = 5, maxHp = 10))

            clock.advance(60_000L)
            scheduler.runDue()

            // Mob should still be in registry, but hp should NOT be reset to maxHp
            // (the guard skipped the respawn, so the zone-reset version is preserved)
            val mob = mobs.get(mobId)
            assertNotNull(mob, "Mob from zone reset should still be present")
            assertEquals(5, mob!!.hp, "Zone-reset mob's hp should be preserved (respawn skipped)")
        }

    @Test
    fun `respawn is skipped when origin room no longer exists`() =
        runTest {
            val missingRoomId = RoomId("zone:missing")
            val spawn = MobSpawn(id = mobId, name = "a rat", roomId = missingRoomId, respawnSeconds = 30L)
            // World does NOT contain the mob's origin room
            val anchorRoom = Room(roomId, "A Room", "A room.", emptyMap())
            val world = World(mapOf(roomId to anchorRoom), roomId, mobSpawns = listOf(spawn))

            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val clock = MutableClock(0L)
            val scheduler = Scheduler(clock)
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), ItemRegistry())
            val mobSystem = MobSystem()

            val onMobRemoved = buildOnMobRemoved(world, mobs, mobSystem, scheduler, players, outbound)

            onMobRemoved(mobId, roomId)

            clock.advance(30_000L)
            scheduler.runDue()

            assertNull(mobs.get(mobId), "Mob should not respawn into a missing room")
        }

    @Test
    fun `mob without respawnSeconds does not schedule a respawn`() =
        runTest {
            val room = Room(roomId, "A Room", "A room.", emptyMap())
            val spawn = MobSpawn(id = mobId, name = "a rat", roomId = roomId) // respawnSeconds = null
            val world = World(mapOf(roomId to room), roomId, mobSpawns = listOf(spawn))

            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val clock = MutableClock(0L)
            val scheduler = Scheduler(clock)
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), ItemRegistry())
            val mobSystem = MobSystem()

            val onMobRemoved = buildOnMobRemoved(world, mobs, mobSystem, scheduler, players, outbound)

            mobs.upsert(MobState(mobId, "a rat", roomId))
            mobs.remove(mobId)
            onMobRemoved(mobId, roomId)

            assertEquals(0, scheduler.size(), "No respawn should be scheduled when respawnSeconds is null")
        }

    @Test
    fun `arrival message is sent to players in origin room when mob respawns`() =
        runTest {
            val room = Room(roomId, "A Room", "A room.", emptyMap())
            val spawn = MobSpawn(id = mobId, name = "a rat", roomId = roomId, respawnSeconds = 10L)
            val world = World(mapOf(roomId to room), roomId, mobSpawns = listOf(spawn))

            val mobs = MobRegistry()
            val outbound = LocalOutboundBus()
            val clock = MutableClock(0L)
            val scheduler = Scheduler(clock)
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), ItemRegistry())
            val mobSystem = MobSystem()

            val onMobRemoved = buildOnMobRemoved(world, mobs, mobSystem, scheduler, players, outbound)

            // Log a player into the origin room (PlayerRegistry startRoom = roomId)
            val playerSid = SessionId(1L)
            val loginResult = players.login(playerSid, "Hero", "password")
            require(loginResult == LoginResult.Ok) { "Login failed: $loginResult" }

            mobs.upsert(MobState(mobId, "a rat", roomId))
            mobs.remove(mobId)
            onMobRemoved(mobId, roomId)

            clock.advance(10_000L)
            scheduler.runDue()

            // Drain all messages and find the arrival announcement
            val messages = mutableListOf<String>()
            while (true) {
                val event = outbound.tryReceive().getOrNull() ?: break
                if (event is OutboundEvent.SendText) messages += event.text
            }
            val hasArrival = messages.any { it.contains("a rat appears", ignoreCase = true) }
            assertEquals(true, hasArrival, "Expected arrival message. Got: $messages")
        }
}
