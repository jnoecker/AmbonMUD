package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.GuildHallTemplateConfig
import dev.ambon.config.GuildHallsConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryGuildRepository
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuildHallSystemTest {
    private val roomId = RoomId("test:start")

    private val hallConfig = GuildHallsConfig(
        enabled = true,
        purchaseCost = 50_000L,
        roomCost = 10_000L,
        maxRooms = 3,
        templates = mapOf(
            "meeting_hall" to GuildHallTemplateConfig(
                title = "Guild Meeting Hall",
                description = "A grand chamber.",
            ),
            "vault" to GuildHallTemplateConfig(
                title = "Guild Vault",
                description = "A reinforced room.",
                hasStorage = true,
            ),
            "training_room" to GuildHallTemplateConfig(
                title = "Training Room",
                description = "Practice dummies.",
            ),
        ),
    )

    private fun setup(startingGold: Long = 100_000L): TestHarness {
        val items = ItemRegistry()
        val playerRepo = InMemoryPlayerRepository()
        val players = buildTestPlayerRegistry(roomId, playerRepo, items, startingGold = startingGold)
        val outbound = LocalOutboundBus()
        val clock = MutableClock(0L)
        val guildRepo = InMemoryGuildRepository()
        val world = World(
            startRoom = roomId,
            rooms = mutableMapOf(
                roomId to Room(id = roomId, title = "Start", description = "A test room.", exits = emptyMap()),
            ),
        )
        val guildSystem = GuildSystem(
            players = players,
            guildRepo = guildRepo,
            outbound = outbound,
            clock = clock,
            maxSize = 50,
        )
        val hallSystem = GuildHallSystem(
            players = players,
            guildRepo = guildRepo,
            world = world,
            outbound = outbound,
            config = hallConfig,
            markPlayerDirty = {},
            guildSystem = guildSystem,
        )
        return TestHarness(players, playerRepo, outbound, clock, guildSystem, hallSystem, guildRepo, items, world)
    }

    private data class TestHarness(
        val players: PlayerRegistry,
        val playerRepo: InMemoryPlayerRepository,
        val outbound: LocalOutboundBus,
        val clock: MutableClock,
        val guild: GuildSystem,
        val hall: GuildHallSystem,
        val guildRepo: InMemoryGuildRepository,
        val items: ItemRegistry,
        val world: World,
    )

    private suspend fun TestHarness.createGuildForAlice(sid: SessionId): String {
        val err = guild.create(sid, "Shadowblade", "SB")
        assertNull(err, "Guild creation should succeed: $err")
        outbound.drainAll()
        return "shadowblade"
    }

    @Test
    fun `buy hall - leader only, gold deducted`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.createGuildForAlice(sid)

        val ps = h.players.get(sid)!!
        val goldBefore = ps.gold

        val err = h.hall.buyHall(sid)
        assertNull(err, "Hall purchase should succeed: $err")

        assertEquals(goldBefore - 50_000, ps.gold)

        val guild = h.guildRepo.findById("shadowblade")!!
        assertEquals(1, guild.hallRooms.size)
        assertEquals("meeting_hall", guild.hallRooms[0].template)

        val events = h.outbound.drainAll()
        assertTrue(events.any { it is OutboundEvent.SendInfo && it.text.contains("guild hall") })

        // Hall room is materialised
        val hallRoomId = RoomId("guild_hall_shadowblade:room_0")
        assertNotNull(h.world.rooms[hallRoomId])
    }

    @Test
    fun `buy hall - non-leader rejected`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        h.createGuildForAlice(sid1)

        // Invite and accept Bob
        h.guild.invite(sid1, "Bob")
        h.guild.accept(sid2)
        h.outbound.drainAll()

        val err = h.hall.buyHall(sid2)
        assertNotNull(err)
        assertTrue(err!!.contains("leader"))
    }

    @Test
    fun `buy hall - insufficient gold`() = runTest {
        val h = setup(startingGold = 1000L)
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.createGuildForAlice(sid)

        val err = h.hall.buyHall(sid)
        assertNotNull(err)
        assertTrue(err!!.contains("gold"))
    }

    @Test
    fun `buy hall - already has hall`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.createGuildForAlice(sid)

        assertNull(h.hall.buyHall(sid))
        h.outbound.drainAll()

        val err = h.hall.buyHall(sid)
        assertNotNull(err)
        assertTrue(err!!.contains("already"))
    }

    @Test
    fun `expand hall - cost deducted and room added`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.createGuildForAlice(sid)
        assertNull(h.hall.buyHall(sid))
        h.outbound.drainAll()

        val ps = h.players.get(sid)!!
        val goldBefore = ps.gold

        val err = h.hall.expandHall(sid, "vault")
        assertNull(err, "Expand should succeed: $err")

        assertEquals(goldBefore - 10_000, ps.gold)

        val guild = h.guildRepo.findById("shadowblade")!!
        assertEquals(2, guild.hallRooms.size)
        assertEquals("vault", guild.hallRooms[1].template)

        // New room materialised
        val vaultRoomId = RoomId("guild_hall_shadowblade:room_1")
        assertNotNull(h.world.rooms[vaultRoomId])
    }

    @Test
    fun `expand hall - room limit enforced`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.createGuildForAlice(sid)
        assertNull(h.hall.buyHall(sid))
        assertNull(h.hall.expandHall(sid, "vault"))
        assertNull(h.hall.expandHall(sid, "training_room"))
        h.outbound.drainAll()

        // max is 3, already have 3 rooms
        val err = h.hall.expandHall(sid, "trophy_room")
        assertNotNull(err)
        assertTrue(err!!.contains("maximum"))
    }

    @Test
    fun `expand hall - duplicate template rejected`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.createGuildForAlice(sid)
        assertNull(h.hall.buyHall(sid))
        assertNull(h.hall.expandHall(sid, "vault"))
        h.outbound.drainAll()

        val err = h.hall.expandHall(sid, "vault")
        assertNotNull(err)
        assertTrue(err!!.contains("already has"))
    }

    @Test
    fun `enter hall - teleports member`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.createGuildForAlice(sid)
        assertNull(h.hall.buyHall(sid))
        h.outbound.drainAll()

        val err = h.hall.enterHall(sid)
        assertNull(err, "Enter should succeed: $err")

        // The system returns null, meaning the caller handles movement
        // Verify hall entry room exists
        val entryRoomId = h.hall.hallEntryRoomId("shadowblade")
        assertEquals(RoomId("guild_hall_shadowblade:room_0"), entryRoomId)
    }

    @Test
    fun `enter hall - non-member rejected`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        h.createGuildForAlice(sid1)
        assertNull(h.hall.buyHall(sid1))
        h.outbound.drainAll()

        // Bob is not in the guild
        val err = h.hall.enterHall(sid2)
        assertNotNull(err)
        assertTrue(err!!.contains("not in a guild"))
    }

    @Test
    fun `leave hall - returns to origin`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.createGuildForAlice(sid)
        assertNull(h.hall.buyHall(sid))
        h.outbound.drainAll()

        assertNull(h.hall.enterHall(sid))
        assertTrue(h.hall.isInAnyHall(sid))

        val origin = h.hall.resolveHallExit(sid)
        assertEquals(roomId, origin)
    }

    @Test
    fun `show hall info - shows rooms and member count`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.createGuildForAlice(sid)
        assertNull(h.hall.buyHall(sid))
        h.outbound.drainAll()

        val err = h.hall.showHallInfo(sid)
        assertNull(err)

        val events = h.outbound.drainAll()
        val info = events.filterIsInstance<OutboundEvent.SendInfo>()
        assertTrue(info.any { it.text.contains("Guild Meeting Hall") })
        assertTrue(info.any { it.text.contains("Rooms (1/3)") })
    }

    @Test
    fun `show hall info - no hall shows purchase hint`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.createGuildForAlice(sid)
        h.outbound.drainAll()

        val err = h.hall.showHallInfo(sid)
        assertNull(err)

        val events = h.outbound.drainAll()
        val info = events.filterIsInstance<OutboundEvent.SendInfo>()
        assertTrue(info.any { it.text.contains("guild hall buy") })
    }

    @Test
    fun `guild hall exit sentinel detection`() = runTest {
        val h = setup()
        assertTrue(h.hall.isGuildHallExit(RoomId("guild_hall_test:exit")))
        assertTrue(h.hall.isGuildHallRoom(RoomId("guild_hall_test:room_0")))
    }

    @Test
    fun `guild disbanded boots visitors from hall`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.createGuildForAlice(sid)
        assertNull(h.hall.buyHall(sid))

        // Enter the hall
        assertNull(h.hall.enterHall(sid))
        val ps = h.players.get(sid)!!
        ps.roomId = RoomId("guild_hall_shadowblade:room_0")
        assertTrue(h.hall.isInAnyHall(sid))
        h.outbound.drainAll()

        // Disband
        h.hall.onGuildDisbanded("shadowblade")

        // Player should no longer be in hall
        assertTrue(!h.hall.isInAnyHall(sid))

        val events = h.outbound.drainAll()
        assertTrue(events.any { it is OutboundEvent.SendInfo && it.text.contains("disbanded") })
    }
}
