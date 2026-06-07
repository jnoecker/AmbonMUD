package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.LeverState
import dev.ambon.domain.world.LockableState
import dev.ambon.domain.world.RoomFeature
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.drainAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimedRespawnHandlerTest {
    private val world = dev.ambon.test.TestWorlds.okTimedRespawn
    private val hall = RoomId("ok_timed:hall")
    private val sid = SessionId(1)

    private data class Harness(
        val players: PlayerRegistry,
        val items: ItemRegistry,
        val worldState: WorldStateRegistry,
        val outbound: LocalOutboundBus,
        val handler: TimedRespawnHandler,
        val clock: MutableClock,
    ) {
        val lever: RoomFeature.Lever =
            worldState.featuresById.values.filterIsInstance<RoomFeature.Lever>().single()
        val chest: RoomFeature.Container =
            worldState.featuresById.values.filterIsInstance<RoomFeature.Container>().single()
        val door: RoomFeature.Door =
            worldState.featuresById.values.filterIsInstance<RoomFeature.Door>().single()
    }

    private suspend fun buildHarness(): Harness {
        val clock = MutableClock(0L)
        val outbound = LocalOutboundBus()
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val players = PlayerRegistry(
            startRoom = world.startRoom,
            repo = InMemoryPlayerRepository(),
            items = items,
            clock = clock,
        )
        val worldState = WorldStateRegistry(world)
        // Seed container initial contents the way GameEngine does at boot.
        for (room in world.rooms.values) {
            for (feature in room.features.filterIsInstance<RoomFeature.Container>()) {
                for (inst in feature.initialItems.mapNotNull { items.createFromTemplate(it) }) {
                    worldState.addToContainer(feature.id, inst)
                }
            }
        }
        val handler = TimedRespawnHandler(
            world = world,
            items = items,
            players = players,
            outbound = outbound,
            worldState = worldState,
            gmcpEmitter = null,
            clock = clock,
        )
        require(players.login(sid, "Tester", "password") == LoginResult.Ok)
        outbound.drainAll()
        return Harness(players, items, worldState, outbound, handler, clock)
    }

    private fun Harness.coinInHall(): Boolean =
        items.itemsInRoom(hall).any { it.item.keyword == "coin" }

    // ---- Item respawn ----

    @Test
    fun `picked-up item respawns in its room after respawnSeconds`() = runTest {
        val h = buildHarness()
        h.items.takeFromRoom(sid, hall, "coin")
        assertFalse(h.coinInHall())

        // Timer arms on the first tick that observes the item missing.
        h.handler.tick()
        h.clock.advance(29_000L)
        h.handler.tick()
        assertFalse(h.coinInHall(), "coin must not respawn before 30s")

        h.clock.advance(2_000L)
        h.handler.tick()
        assertTrue(h.coinInHall(), "coin should respawn after 30s")

        val texts = h.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(
            texts.any { it == "a gleaming coin appears." },
            "players in the room should see the item reappear, got: $texts",
        )
    }

    @Test
    fun `item without respawnSeconds never respawns on a timer`() = runTest {
        val h = buildHarness()
        h.items.takeFromRoom(sid, hall, "relic")

        h.handler.tick()
        h.clock.advance(3_600_000L)
        h.handler.tick()

        assertFalse(
            h.items.itemsInRoom(hall).any { it.item.keyword == "relic" },
            "relic has no respawnSeconds and must wait for a zone reset",
        )
    }

    @Test
    fun `returning the item before the timer fires disarms it`() = runTest {
        val h = buildHarness()
        val coin = h.items.takeFromRoom(sid, hall, "coin")!!
        h.handler.tick()
        h.clock.advance(15_000L)
        h.handler.tick()

        // Player drops it back: the missing-timer must reset.
        h.items.addRoomItem(hall, coin)
        h.handler.tick()

        h.items.takeFromRoom(sid, hall, "coin")
        h.handler.tick()
        h.clock.advance(16_000L) // would fire if the old timer had kept running
        h.handler.tick()
        assertFalse(h.coinInHall(), "re-taking the item must restart the 30s timer")

        h.clock.advance(15_000L)
        h.handler.tick()
        assertTrue(h.coinInHall())
    }

    // ---- Feature reverts ----

    @Test
    fun `pulled lever snaps back after respawnSeconds`() = runTest {
        val h = buildHarness()
        h.worldState.setLeverState(h.lever.id, LeverState.DOWN)

        h.handler.tick()
        h.clock.advance(9_000L)
        h.handler.tick()
        assertEquals(LeverState.DOWN, h.worldState.getLeverState(h.lever.id), "lever must hold for 10s")

        h.clock.advance(2_000L)
        h.handler.tick()
        assertEquals(LeverState.UP, h.worldState.getLeverState(h.lever.id), "lever should snap back after 10s")

        val texts = h.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertTrue(
            texts.any { it == "The sprung lever snaps back into place." },
            "players in the room should see the lever reset, got: $texts",
        )
    }

    @Test
    fun `opened door reverts to its initial state`() = runTest {
        val h = buildHarness()
        h.worldState.setLockableState(h.door.id, LockableState.OPEN)

        h.handler.tick()
        h.clock.advance(21_000L)
        h.handler.tick()

        assertEquals(
            LockableState.CLOSED,
            h.worldState.getLockableState(h.door.id),
            "door should re-close after its 20s respawnSeconds",
        )
    }

    @Test
    fun `looted container relocks and refills after respawnSeconds`() = runTest {
        val h = buildHarness()
        // Open the chest and loot the gem.
        h.worldState.setLockableState(h.chest.id, LockableState.OPEN)
        h.worldState.removeFromContainer(h.chest.id, "gem")
        assertTrue(h.worldState.getContainerContents(h.chest.id).isEmpty())

        h.handler.tick()
        h.clock.advance(16_000L)
        h.handler.tick()

        assertEquals(LockableState.CLOSED, h.worldState.getLockableState(h.chest.id))
        assertEquals(
            listOf("ok_timed:gem"),
            h.worldState.getContainerContents(h.chest.id).map { it.id.value },
            "chest should refill its initial items",
        )
    }

    @Test
    fun `looting an open-state container still arms the timer via contents`() = runTest {
        val h = buildHarness()
        // State untouched (closed = initial), but contents drained: the contents
        // mismatch alone must arm the revert timer.
        h.worldState.removeFromContainer(h.chest.id, "gem")

        h.handler.tick()
        h.clock.advance(16_000L)
        h.handler.tick()

        assertEquals(
            listOf("ok_timed:gem"),
            h.worldState.getContainerContents(h.chest.id).map { it.id.value },
        )
    }

    @Test
    fun `untouched features never revert`() = runTest {
        val h = buildHarness()
        h.outbound.drainAll()

        h.handler.tick()
        h.clock.advance(3_600_000L)
        h.handler.tick()

        assertEquals(LeverState.UP, h.worldState.getLeverState(h.lever.id))
        assertEquals(LockableState.CLOSED, h.worldState.getLockableState(h.chest.id))
        val texts = h.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>()
        assertTrue(texts.isEmpty(), "no revert messages expected for untouched features, got: $texts")
    }
}
