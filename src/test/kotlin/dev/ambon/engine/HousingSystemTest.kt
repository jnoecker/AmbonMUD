package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.HousingConfig
import dev.ambon.config.RoomTemplateConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryHouseRepository
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HousingSystemTest {
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
            "workshop" to RoomTemplateConfig(
                title = "Crafting Workshop",
                description = "A workshop.",
                cost = 150,
                station = "forge",
                safe = true,
            ),
        ),
    )

    private fun setup(): TestHarness {
        val items = ItemRegistry()
        val playerRepo = InMemoryPlayerRepository()
        val players = buildTestPlayerRegistry(startRoom, playerRepo, items, startingGold = 1000)
        val outbound = LocalOutboundBus()
        val clock = MutableClock(0L)
        val houseRepo = InMemoryHouseRepository()

        val startRoomObj = Room(
            id = startRoom,
            title = "Start",
            description = "The start room.",
            exits = emptyMap(),
        )
        val world = World(
            rooms = mapOf(startRoom to startRoomObj),
            startRoom = startRoom,
        )

        val housing = HousingSystem(
            players = players,
            houseRepo = houseRepo,
            world = world,
            outbound = outbound,
            config = housingConfig,
            clock = clock,
        )
        return TestHarness(players, playerRepo, outbound, clock, housing, houseRepo, world, items)
    }

    private data class TestHarness(
        val players: PlayerRegistry,
        val playerRepo: InMemoryPlayerRepository,
        val outbound: LocalOutboundBus,
        val clock: MutableClock,
        val housing: HousingSystem,
        val houseRepo: InMemoryHouseRepository,
        val world: World,
        val items: ItemRegistry,
    )

    // -------- creation --------

    @Test
    fun `create house deducts gold and creates entry room`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.outbound.drainAll()

            val err = h.housing.createHouse(sid)
            assertNull(err)

            val ps = h.players.get(sid)!!
            assertTrue(ps.hasHouse)
            assertEquals(900, ps.gold) // 1000 - 100

            val record = h.houseRepo.findByOwnerId(ps.playerId!!)
            assertNotNull(record)
            assertEquals("Alice", record!!.ownerName)
            assertEquals(1, record.rooms.size)
            assertEquals("cottage_entry", record.rooms[0].templateId)

            // Room should be materialized in world
            val houseRoomId = RoomId("house_Alice:room_0")
            val room = h.world.rooms[houseRoomId]
            assertNotNull(room)
            assertEquals("Cottage Entryway", room!!.title)
            assertTrue(room.exits.containsKey(Direction.SOUTH))
        }

    @Test
    fun `create house fails if already owns one`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")

            assertNull(h.housing.createHouse(sid))
            val err = h.housing.createHouse(sid)
            assertEquals("You already own a house.", err)
        }

    @Test
    fun `create house fails with insufficient gold`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.players.get(sid)!!.gold = 50

            val err = h.housing.createHouse(sid)
            assertNotNull(err)
            assertTrue(err!!.contains("gold"))
        }

    // -------- expansion --------

    @Test
    fun `expand house adds room in chosen direction`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.housing.createHouse(sid)

            // Enter house
            val ps = h.players.get(sid)!!
            val entryResult = h.housing.enterOwnHouse(sid, startRoom)
            assertTrue(entryResult is RoomId)
            ps.roomId = entryResult as RoomId

            val err = h.housing.expandHouse(sid, "vault", Direction.NORTH)
            assertNull(err)

            assertEquals(700, ps.gold) // 1000 - 100 (house) - 200 (vault)

            val record = h.houseRepo.findByOwnerId(ps.playerId!!)!!
            assertEquals(2, record.rooms.size)
            assertEquals("vault", record.rooms[1].templateId)

            // Check exits
            assertEquals(mapOf(Direction.NORTH to 1), record.rooms[0].exits)
            assertEquals(mapOf(Direction.SOUTH to 0), record.rooms[1].exits)

            // Check world rooms
            val vaultRoom = h.world.rooms[RoomId("house_Alice:room_1")]
            assertNotNull(vaultRoom)
            assertEquals("Storage Vault", vaultRoom!!.title)
        }

    @Test
    fun `expand fails if not in own house`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.housing.createHouse(sid)
            // Don't enter house

            val err = h.housing.expandHouse(sid, "vault", Direction.NORTH)
            assertNotNull(err)
            assertTrue(err!!.contains("inside your house"))
        }

    @Test
    fun `expand fails for duplicate template`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.housing.createHouse(sid)

            val ps = h.players.get(sid)!!
            val entryRoomId = h.housing.enterOwnHouse(sid, startRoom) as RoomId
            ps.roomId = entryRoomId

            assertNull(h.housing.expandHouse(sid, "vault", Direction.NORTH))
            val err = h.housing.expandHouse(sid, "vault", Direction.EAST)
            assertNotNull(err)
            assertTrue(err!!.contains("already have"))
        }

    @Test
    fun `expand fails if direction already used`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.housing.createHouse(sid)

            val ps = h.players.get(sid)!!
            val entryRoomId = h.housing.enterOwnHouse(sid, startRoom) as RoomId
            ps.roomId = entryRoomId

            assertNull(h.housing.expandHouse(sid, "vault", Direction.NORTH))
            val err = h.housing.expandHouse(sid, "workshop", Direction.NORTH)
            assertNotNull(err)
            assertTrue(err!!.contains("already an exit"))
        }

    @Test
    fun `expand fails if direction is reserved for house exit`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.housing.createHouse(sid)

            val ps = h.players.get(sid)!!
            val entryRoomId = h.housing.enterOwnHouse(sid, startRoom) as RoomId
            ps.roomId = entryRoomId

            val err = h.housing.expandHouse(sid, "vault", Direction.SOUTH)
            assertNotNull(err)
            assertTrue(err!!.contains("reserved"))
        }

    // -------- customization --------

    @Test
    fun `set room title persists`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.housing.createHouse(sid)

            val ps = h.players.get(sid)!!
            val entryRoomId = h.housing.enterOwnHouse(sid, startRoom) as RoomId
            ps.roomId = entryRoomId

            val err = h.housing.setRoomTitle(sid, "My Cozy Cabin")
            assertNull(err)

            val record = h.houseRepo.findByOwnerId(ps.playerId!!)!!
            assertEquals("My Cozy Cabin", record.rooms[0].customTitle)

            val worldRoom = h.world.rooms[entryRoomId]!!
            assertEquals("My Cozy Cabin", worldRoom.title)
        }

    @Test
    fun `set room description persists`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.housing.createHouse(sid)

            val ps = h.players.get(sid)!!
            val entryRoomId = h.housing.enterOwnHouse(sid, startRoom) as RoomId
            ps.roomId = entryRoomId

            val err = h.housing.setRoomDescription(sid, "A warm and inviting space.")
            assertNull(err)

            val record = h.houseRepo.findByOwnerId(ps.playerId!!)!!
            assertEquals("A warm and inviting space.", record.rooms[0].customDescription)
        }

    // -------- entry & exit --------

    @Test
    fun `enter own house tracks origin`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.housing.createHouse(sid)

            val result = h.housing.enterOwnHouse(sid, startRoom)
            assertTrue(result is RoomId)
            assertEquals(RoomId("house_Alice:room_0"), result)
            assertTrue(h.housing.isInAnyHouse(sid))
        }

    @Test
    fun `resolve exit returns origin room`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.housing.createHouse(sid)

            h.housing.enterOwnHouse(sid, startRoom)
            val exit = h.housing.resolveHouseExit(sid)
            assertEquals(startRoom, exit)
            assertFalse(h.housing.isInAnyHouse(sid))
        }

    @Test
    fun `isHouseExit detects sentinel`() =
        runTest {
            val h = setup()
            assertTrue(h.housing.isHouseExit(RoomId("house_Alice:exit")))
            assertFalse(h.housing.isHouseExit(RoomId("zone:room")))
        }

    // -------- visitors --------

    @Test
    fun `invite adds visitor with origin tracking`() =
        runTest {
            val h = setup()
            val ownerSid = SessionId(1L)
            val visitorSid = SessionId(2L)
            h.players.loginOrFail(ownerSid, "Alice")
            h.players.loginOrFail(visitorSid, "Bob")
            h.housing.createHouse(ownerSid)

            val ps = h.players.get(ownerSid)!!
            val entryRoomId = h.housing.enterOwnHouse(ownerSid, startRoom) as RoomId
            ps.roomId = entryRoomId

            val err = h.housing.invitePlayer(ownerSid, "Bob")
            assertNull(err)
            assertTrue(h.housing.isInAnyHouse(visitorSid))

            val guests = h.housing.guestList(ownerSid)
            assertEquals(listOf("Bob"), guests)
        }

    @Test
    fun `kick removes visitor`() =
        runTest {
            val h = setup()
            val ownerSid = SessionId(1L)
            val visitorSid = SessionId(2L)
            h.players.loginOrFail(ownerSid, "Alice")
            h.players.loginOrFail(visitorSid, "Bob")
            h.housing.createHouse(ownerSid)

            val ps = h.players.get(ownerSid)!!
            val entryRoomId = h.housing.enterOwnHouse(ownerSid, startRoom) as RoomId
            ps.roomId = entryRoomId

            h.housing.invitePlayer(ownerSid, "Bob")
            h.outbound.drainAll()

            val err = h.housing.kickVisitor(ownerSid, "Bob")
            assertNull(err)
            assertFalse(h.housing.isInAnyHouse(visitorSid))

            val events = h.outbound.drainAll()
            assertTrue(events.any { it is OutboundEvent.SendInfo && it.sessionId == visitorSid })
        }

    // -------- disconnect --------

    @Test
    fun `owner disconnect boots visitors`() =
        runTest {
            val h = setup()
            val ownerSid = SessionId(1L)
            val visitorSid = SessionId(2L)
            h.players.loginOrFail(ownerSid, "Alice")
            h.players.loginOrFail(visitorSid, "Bob")
            h.housing.createHouse(ownerSid)

            val ps = h.players.get(ownerSid)!!
            val entryRoomId = h.housing.enterOwnHouse(ownerSid, startRoom) as RoomId
            ps.roomId = entryRoomId

            h.housing.invitePlayer(ownerSid, "Bob")
            h.outbound.drainAll()

            h.housing.onPlayerDisconnected(ownerSid)
            assertFalse(h.housing.isInAnyHouse(visitorSid))

            val bobState = h.players.get(visitorSid)!!
            assertEquals(startRoom, bobState.roomId) // Returned to origin
        }

    // -------- status --------

    @Test
    fun `house status returns room info`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.housing.createHouse(sid)

            val status = h.housing.houseStatus(sid)
            assertNotNull(status)
            assertEquals("Alice", status!!.ownerName)
            assertEquals(1, status.rooms.size)
            assertEquals("Cottage Entryway", status.rooms[0].title)
        }

    @Test
    fun `house status returns null without house`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")

            val status = h.housing.houseStatus(sid)
            assertNull(status)
        }

    // -------- session remap --------

    @Test
    fun `remap session transfers visitor state`() =
        runTest {
            val h = setup()
            val oldSid = SessionId(1L)
            val newSid = SessionId(2L)
            h.players.loginOrFail(oldSid, "Alice")
            h.housing.createHouse(oldSid)
            h.housing.enterOwnHouse(oldSid, startRoom)

            h.housing.remapSession(oldSid, newSid)

            assertFalse(h.housing.isInAnyHouse(oldSid))
            assertTrue(h.housing.isInAnyHouse(newSid))
            val exit = h.housing.resolveHouseExit(newSid)
            assertEquals(startRoom, exit)
        }

    // -------- login loading --------

    @Test
    fun `onPlayerLogin loads house and materializes rooms`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")

            // Create house first
            h.housing.createHouse(sid)
            val ps = h.players.get(sid)!!

            // Simulate re-login: clear cached state
            ps.hasHouse = false

            h.housing.onPlayerLogin(sid)
            assertTrue(ps.hasHouse)

            val room = h.world.rooms[RoomId("house_Alice:room_0")]
            assertNotNull(room)
        }
}
