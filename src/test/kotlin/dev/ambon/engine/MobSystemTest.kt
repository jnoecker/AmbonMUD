package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MobSystemTest {
    @Test
    fun `tick schedules mob and waits for delay`() =
        runTest {
            val roomA = Room(RoomId("zone:a"), "A", "A", mapOf(Direction.NORTH to RoomId("zone:b")))
            val roomB = Room(RoomId("zone:b"), "B", "B", mapOf(Direction.SOUTH to RoomId("zone:a")))
            val world = World(mapOf(roomA.id to roomA, roomB.id to roomB), roomA.id)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:rat"), "a rat", roomA.id)
            mobs.upsert(mob)

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), ItemRegistry())
            val outbound = LocalOutboundBus()
            val clock = MutableClock(0L)
            val system =
                MobSystem(
                    world,
                    mobs,
                    players,
                    outbound,
                    clock = clock,
                    rng = Random(1),
                    minWanderDelayMillis = 1_000L,
                    maxWanderDelayMillis = 1_000L,
                )

            system.tick()
            assertEquals(roomA.id, mob.roomId)
            assertTrue(outbound.tryReceive().getOrNull() == null)

            clock.advance(1_000L)
            system.tick()

            assertEquals(roomB.id, mob.roomId)
        }

    @Test
    fun `tick moves mob and notifies players in from and to rooms`() =
        runTest {
            val roomA = Room(RoomId("zone:a"), "A", "A", mapOf(Direction.NORTH to RoomId("zone:b")))
            val roomB = Room(RoomId("zone:b"), "B", "B", mapOf(Direction.SOUTH to RoomId("zone:a")))
            val world = World(mapOf(roomA.id to roomA, roomB.id to roomB), roomA.id)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:rat"), "a rat", roomA.id)
            mobs.upsert(mob)

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), ItemRegistry())
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            login(players, sid1, "Player1")
            login(players, sid2, "Player2")
            players.moveTo(sid2, roomB.id)

            val outbound = LocalOutboundBus()
            val system =
                MobSystem(
                    world,
                    mobs,
                    players,
                    outbound,
                    clock = MutableClock(0L),
                    rng = Random(1),
                    minWanderDelayMillis = 0L,
                    maxWanderDelayMillis = 0L,
                )

            system.tick()
            system.tick()

            val got = mutableListOf<OutboundEvent>()
            while (true) {
                val next = outbound.tryReceive().getOrNull() ?: break
                got += next
            }

            assertTrue(got.any { it is OutboundEvent.SendText && it.sessionId == sid1 && it.text == "a rat leaves." })
            assertTrue(got.any { it is OutboundEvent.SendText && it.sessionId == sid2 && it.text == "a rat enters." })
        }

    @Test
    fun `tick does not move mobs in rooms without exits`() =
        runTest {
            val roomA = Room(RoomId("zone:a"), "A", "A", emptyMap())
            val world = World(mapOf(roomA.id to roomA), roomA.id)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:owl"), "an owl", roomA.id)
            mobs.upsert(mob)

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), ItemRegistry())
            val outbound = LocalOutboundBus()
            val system =
                MobSystem(
                    world,
                    mobs,
                    players,
                    outbound,
                    clock = MutableClock(0L),
                    rng = Random(1),
                    minWanderDelayMillis = 0L,
                    maxWanderDelayMillis = 0L,
                )

            system.tick()
            system.tick()

            assertEquals(roomA.id, mob.roomId)
            assertTrue(outbound.tryReceive().getOrNull() == null)
        }

    @Test
    fun `tick does not move mobs that are in combat`() =
        runTest {
            val roomA = Room(RoomId("zone:a"), "A", "A", mapOf(Direction.NORTH to RoomId("zone:b")))
            val roomB = Room(RoomId("zone:b"), "B", "B", mapOf(Direction.SOUTH to RoomId("zone:a")))
            val world = World(mapOf(roomA.id to roomA, roomB.id to roomB), roomA.id)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:rat"), "a rat", roomA.id)
            mobs.upsert(mob)

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), ItemRegistry())
            val outbound = LocalOutboundBus()
            val system =
                MobSystem(
                    world,
                    mobs,
                    players,
                    outbound,
                    clock = MutableClock(0L),
                    rng = Random(1),
                    isMobInCombat = { true },
                    minWanderDelayMillis = 0L,
                    maxWanderDelayMillis = 0L,
                )

            system.tick()
            system.tick()

            assertEquals(roomA.id, mob.roomId)
            assertTrue(outbound.tryReceive().getOrNull() == null)
        }

    @Test
    fun `stationary mob does not wander`() =
        runTest {
            val roomA = Room(RoomId("zone:a"), "A", "A", mapOf(Direction.NORTH to RoomId("zone:b")))
            val roomB = Room(RoomId("zone:b"), "B", "B", mapOf(Direction.SOUTH to RoomId("zone:a")))
            val world = World(mapOf(roomA.id to roomA, roomB.id to roomB), roomA.id)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:npc"), "a friendly npc", roomA.id, stationary = true)
            mobs.upsert(mob)

            val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), ItemRegistry())
            val outbound = LocalOutboundBus()
            val clock = MutableClock(0L)
            val system =
                MobSystem(
                    world,
                    mobs,
                    players,
                    outbound,
                    clock = clock,
                    rng = Random(1),
                    minWanderDelayMillis = 0L,
                    maxWanderDelayMillis = 0L,
                )

            // Tick multiple times — stationary mob should never move
            system.tick()
            clock.advance(1_000L)
            system.tick()
            clock.advance(1_000L)
            system.tick()

            assertEquals(roomA.id, mob.roomId)
            assertTrue(outbound.tryReceive().getOrNull() == null)
        }

    private suspend fun login(
        players: PlayerRegistry,
        sessionId: SessionId,
        name: String,
    ) {
        val res = players.login(sessionId, name, "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
    }
}
