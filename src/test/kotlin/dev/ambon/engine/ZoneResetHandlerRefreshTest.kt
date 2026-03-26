package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZoneResetHandlerRefreshTest {
    private val roomA = RoomId("zone1:roomA")
    private val roomB = RoomId("zone2:roomB")

    private fun buildHandler(
        world: World,
        clock: MutableClock,
    ): ZoneResetHandler {
        val outbound = LocalOutboundBus()
        val mobs = MobRegistry()
        val items = ItemRegistry()
        val players = PlayerRegistry(
            startRoom = world.startRoom,
            repo = InMemoryPlayerRepository(),
            items = items,
            clock = clock,
        )
        val behaviorTreeSystem = BehaviorTreeSystem(
            world = world,
            mobs = mobs,
            players = players,
            outbound = outbound,
            clock = clock,
        )
        val mobSystem = MobSystem()
        val combatSystem = CombatSystem(
            players = players,
            mobs = mobs,
            items = items,
            outbound = outbound,
            clock = clock,
        )
        val dialogueSystem = DialogueSystem(
            mobs = mobs,
            players = players,
            outbound = outbound,
        )
        val statusEffectSystem = StatusEffectSystem(
            registry = StatusEffectRegistry(),
            players = players,
            mobs = mobs,
            outbound = outbound,
            clock = clock,
        )
        val worldState = WorldStateRegistry(world)
        val gmcpEmitter = GmcpEmitter(
            outbound = outbound,
            supportsPackage = { _, _ -> false },
        )
        val mobRemovalCoordinator = MobRemovalCoordinator(
            combatSystem = combatSystem,
            dialogueSystem = dialogueSystem,
            behaviorTreeSystem = behaviorTreeSystem,
            mobs = mobs,
            mobSystem = mobSystem,
            statusEffectSystem = statusEffectSystem,
        )
        return ZoneResetHandler(
            world = world,
            mobs = mobs,
            items = items,
            players = players,
            outbound = outbound,
            worldState = worldState,
            mobRemovalCoordinator = mobRemovalCoordinator,
            mobSystem = mobSystem,
            behaviorTreeSystem = behaviorTreeSystem,
            gmcpEmitter = gmcpEmitter,
            clock = clock,
        )
    }

    @Test
    fun `initial schedule matches zoneLifespansMinutes`() {
        val clock = MutableClock(1000L)
        val world = World(
            rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
            startRoom = roomA,
            zoneLifespansMinutes = mapOf("zone1" to 30L),
        )
        val handler = buildHandler(world, clock)

        assertEquals(setOf("zone1"), handler.scheduledZones())
        assertEquals(1000L + 30 * 60_000L, handler.dueAtMillis("zone1"))
    }

    @Test
    fun `refreshSchedule adds new zones`() {
        val clock = MutableClock(1000L)
        val world = World(
            rooms = mapOf(
                roomA to Room(roomA, "A", "desc", emptyMap()),
                roomB to Room(roomB, "B", "desc", emptyMap()),
            ),
            startRoom = roomA,
            zoneLifespansMinutes = mapOf("zone1" to 30L),
        )
        val handler = buildHandler(world, clock)

        assertEquals(setOf("zone1"), handler.scheduledZones())

        // Simulate hot reload adding zone2 lifespan
        clock.advance(5_000L)
        world.replaceAll(
            World(
                rooms = mapOf(
                    roomA to Room(roomA, "A", "desc", emptyMap()),
                    roomB to Room(roomB, "B", "desc", emptyMap()),
                ),
                startRoom = roomA,
                zoneLifespansMinutes = mapOf("zone1" to 30L, "zone2" to 60L),
            ),
        )
        handler.refreshSchedule()

        assertEquals(setOf("zone1", "zone2"), handler.scheduledZones())
        // New zone timer starts from now (6000ms) + 60 * 60_000
        assertEquals(6000L + 60 * 60_000L, handler.dueAtMillis("zone2"))
        // Existing unchanged zone keeps original timer
        assertEquals(1000L + 30 * 60_000L, handler.dueAtMillis("zone1"))
    }

    @Test
    fun `refreshSchedule removes zones no longer in config`() {
        val clock = MutableClock(1000L)
        val world = World(
            rooms = mapOf(
                roomA to Room(roomA, "A", "desc", emptyMap()),
                roomB to Room(roomB, "B", "desc", emptyMap()),
            ),
            startRoom = roomA,
            zoneLifespansMinutes = mapOf("zone1" to 30L, "zone2" to 60L),
        )
        val handler = buildHandler(world, clock)

        assertEquals(setOf("zone1", "zone2"), handler.scheduledZones())

        // Simulate hot reload removing zone2 lifespan
        world.replaceAll(
            World(
                rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
                startRoom = roomA,
                zoneLifespansMinutes = mapOf("zone1" to 30L),
            ),
        )
        handler.refreshSchedule()

        assertEquals(setOf("zone1"), handler.scheduledZones())
        assertNull(handler.dueAtMillis("zone2"))
    }

    @Test
    fun `refreshSchedule resets timer when lifespan changes`() {
        val clock = MutableClock(1000L)
        val world = World(
            rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
            startRoom = roomA,
            zoneLifespansMinutes = mapOf("zone1" to 30L),
        )
        val handler = buildHandler(world, clock)

        val originalDue = handler.dueAtMillis("zone1")!!

        // Advance time and change lifespan from 30 to 45 minutes
        clock.advance(10_000L)
        world.replaceAll(
            World(
                rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
                startRoom = roomA,
                zoneLifespansMinutes = mapOf("zone1" to 45L),
            ),
        )
        handler.refreshSchedule()

        val newDue = handler.dueAtMillis("zone1")!!
        // Timer should be reset to now + new lifespan, not the old due time
        assertEquals(11_000L + 45 * 60_000L, newDue)
        assertTrue(newDue != originalDue)
    }

    @Test
    fun `refreshSchedule keeps timer when lifespan is unchanged`() {
        val clock = MutableClock(1000L)
        val world = World(
            rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
            startRoom = roomA,
            zoneLifespansMinutes = mapOf("zone1" to 30L),
        )
        val handler = buildHandler(world, clock)

        val originalDue = handler.dueAtMillis("zone1")!!

        // Advance time but keep same lifespan
        clock.advance(10_000L)
        world.replaceAll(
            World(
                rooms = mapOf(roomA to Room(roomA, "A updated", "desc", emptyMap())),
                startRoom = roomA,
                zoneLifespansMinutes = mapOf("zone1" to 30L),
            ),
        )
        handler.refreshSchedule()

        // Timer should be unchanged
        assertEquals(originalDue, handler.dueAtMillis("zone1"))
    }

    @Test
    fun `refreshSchedule removes zone when lifespan set to zero`() {
        val clock = MutableClock(1000L)
        val world = World(
            rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
            startRoom = roomA,
            zoneLifespansMinutes = mapOf("zone1" to 30L),
        )
        val handler = buildHandler(world, clock)

        assertNotNull(handler.dueAtMillis("zone1"))

        // Set lifespan to 0 (disabled)
        world.replaceAll(
            World(
                rooms = mapOf(roomA to Room(roomA, "A", "desc", emptyMap())),
                startRoom = roomA,
                zoneLifespansMinutes = mapOf("zone1" to 0L),
            ),
        )
        handler.refreshSchedule()

        assertTrue(handler.scheduledZones().isEmpty())
    }
}
