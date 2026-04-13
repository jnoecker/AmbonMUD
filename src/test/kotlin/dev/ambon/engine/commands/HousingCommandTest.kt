package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.HousingConfig
import dev.ambon.config.RoomTemplateConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.HousingSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryHouseRepository
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.MutableClock
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.errorMessages
import dev.ambon.test.infoMessages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HousingCommandTest {
    private val startRoom = RoomId("zone:start")

    private val housingConfig = HousingConfig(
        enabled = true,
        entryExitDirection = Direction.SOUTH,
        templates = mapOf(
            "cottage_entry" to RoomTemplateConfig(
                title = "Cottage Entryway",
                description = "A cozy entryway.",
                cost = 100,
                isEntry = true,
                safe = true,
            ),
            "vault" to RoomTemplateConfig(
                title = "Storage Vault",
                description = "A reinforced room.",
                cost = 200,
                maxDroppedItems = 50,
                safe = true,
            ),
        ),
    )

    private fun makeWorld(): World {
        val rooms = mapOf(
            startRoom to Room(
                id = startRoom,
                title = "Town Square",
                description = "The town square.",
                exits = emptyMap(),
                housingBroker = true,
            ),
        )
        return World(rooms = rooms, startRoom = startRoom)
    }

    private fun harness(): Triple<CommandRouterHarness, HousingSystem, InMemoryHouseRepository> {
        val world = makeWorld()
        val items = ItemRegistry()
        val playerRepo = InMemoryPlayerRepository()
        val outbound = LocalOutboundBus()
        val clock = MutableClock(0L)
        val houseRepo = InMemoryHouseRepository()
        val players = buildTestPlayerRegistry(startRoom, playerRepo, items, clock = clock, startingGold = 1000)

        val housing = HousingSystem(
            players = players,
            houseRepo = houseRepo,
            world = world,
            outbound = outbound,
            items = items,
            config = housingConfig,
            clock = clock,
        )

        val h = CommandRouterHarness.create(
            world = world,
            repo = playerRepo,
            items = items,
            players = players,
            outbound = outbound,
            clock = clock,
            housingSystem = housing,
        )
        return Triple(h, housing, houseRepo)
    }

    @Test
    fun `house status without a house shows no-house message`() = runTest {
        val (h, _, _) = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.drain()

        h.router.handle(sid, Command.House.Status)

        val infos = h.drain().infoMessages(sid)
        assertTrue(infos.any { it.contains("don't own") }, "got=$infos")
    }

    @Test
    fun `house buy creates a house and confirms`() = runTest {
        val (h, _, _) = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.drain()

        h.router.handle(sid, Command.House.Buy)

        val infos = h.drain().infoMessages(sid)
        assertTrue(infos.any { it.contains("homeowner") }, "got=$infos")
        assertTrue(h.players.get(sid)!!.hasHouse, "Player should own a house")
    }

    @Test
    fun `house buy fails with insufficient gold`() = runTest {
        val (h, _, _) = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.players.get(sid)!!.gold = 10 // Entry costs 100
        h.drain()

        h.router.handle(sid, Command.House.Buy)

        val errors = h.drain().errorMessages(sid)
        assertTrue(errors.any { it.contains("gold") }, "got=$errors")
    }

    @Test
    fun `house buy fails if already own a house`() = runTest {
        val (h, _, _) = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.drain()

        // Buy first house
        h.router.handle(sid, Command.House.Buy)
        h.drain()

        // Try to buy again
        h.router.handle(sid, Command.House.Buy)

        val errors = h.drain().errorMessages(sid)
        assertTrue(errors.any { it.contains("already") }, "got=$errors")
    }

    @Test
    fun `house status after buying shows rooms`() = runTest {
        val (h, _, _) = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.drain()

        h.router.handle(sid, Command.House.Buy)
        h.drain()

        h.router.handle(sid, Command.House.Status)

        val events = h.drain()
        val texts = events.filterIsInstance<OutboundEvent.SendText>()
            .filter { it.sessionId == sid }
            .map { it.text }
        val infos = events.infoMessages(sid)

        assertTrue(texts.any { it.contains("Alice") && it.contains("House") }, "got texts=$texts infos=$infos")
        assertTrue(infos.any { it.contains("Rooms") }, "got=$infos")
    }

    @Test
    fun `house list templates shows available templates`() = runTest {
        val (h, _, _) = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.drain()

        h.router.handle(sid, Command.House.ListTemplates)

        val events = h.drain()
        val infos = events.infoMessages(sid)

        assertTrue(infos.any { it.contains("Cottage Entryway") }, "got=$infos")
        assertTrue(infos.any { it.contains("Storage Vault") }, "got=$infos")
    }

    @Test
    fun `house guests shows no visitors initially`() = runTest {
        val (h, housing, _) = harness()
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.drain()

        h.router.handle(sid, Command.House.Buy)
        h.drain()

        // Need to be in the house to check guests
        val entryRoom = housing.entryRoomId("Alice")
        h.players.moveTo(sid, entryRoom)

        h.router.handle(sid, Command.House.Guests)

        val infos = h.drain().infoMessages(sid)
        assertTrue(infos.any { it.contains("no visitors") }, "got=$infos")
    }

    @Test
    fun `housing not registered when system is null produces no handler output`() = runTest {
        val world = makeWorld()
        val h = CommandRouterHarness.create(world = world)
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.drain()

        h.router.handle(sid, Command.House.Status)

        val events = h.drain()
        // With no HousingHandler registered, the command is silently ignored (only a prompt is sent)
        val errors = events.errorMessages(sid)
        val infos = events.infoMessages(sid)
        assertTrue(errors.isEmpty() && infos.isEmpty(), "No handler output expected. errors=$errors infos=$infos")
    }
}
