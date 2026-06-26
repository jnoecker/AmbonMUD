package dev.ambon.engine.commands

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.BoatRoute
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.BoatSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.buildTestPlayerRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoatCommandTest {
    @Nested
    inner class Parser {
        @Test
        fun `voyages parses to Boat List`() {
            assertEquals(Command.Boat.List, CommandParser.parse("voyages"))
        }

        @Test
        fun `sail with destination parses to Travel`() {
            val result = CommandParser.parse("sail wharf")
            assertTrue(result is Command.Boat.Travel)
            assertEquals("wharf", (result as Command.Boat.Travel).destination)
        }

        @Test
        fun `sail with numeric index parses to Travel`() {
            val result = CommandParser.parse("sail 2")
            assertTrue(result is Command.Boat.Travel)
            assertEquals("2", (result as Command.Boat.Travel).destination)
        }

        @Test
        fun `sail without arg returns Invalid`() {
            assertTrue(CommandParser.parse("sail") is Command.Invalid)
        }

        @Test
        fun `bare south does not trigger a boat command`() {
            assertFalse(CommandParser.parse("s") is Command.Boat)
        }
    }

    @Nested
    inner class System {
        @Test
        fun `isBoatDock is true only for dock rooms`() {
            val system = BoatSystem(boatWorld())
            assertTrue(system.isBoatDock(harbor))
            assertFalse(system.isBoatDock(inland))
            assertFalse(system.isBoatDock(RoomId("coast:nowhere")))
        }

        @Test
        fun `destinationsFrom lists authored routes in order with flat prices`() {
            val dests = BoatSystem(boatWorld()).destinationsFrom(harbor)
            assertEquals(listOf("Wharf", "Pier"), dests.map { it.name }, "authored order preserved")
            assertEquals(50L, dests.first { it.name == "Wharf" }.price)
            assertEquals(120L, dests.first { it.name == "Pier" }.price)
        }

        @Test
        fun `destinationsFrom carries the destination room pin and leaves unpinned ports null`() {
            val dests = BoatSystem(boatWorld()).destinationsFrom(harbor)
            val wharfDest = dests.first { it.name == "Wharf" }
            val pierDest = dests.first { it.name == "Pier" }
            assertEquals(55.0, wharfDest.mapX)
            assertEquals(60.0, wharfDest.mapY)
            assertNull(pierDest.mapX, "unpinned port has no map x")
            assertNull(pierDest.mapY, "unpinned port has no map y")
        }

        @Test
        fun `destinationsFrom skips routes whose destination is not loaded`() {
            val dests = BoatSystem(boatWorld()).destinationsFrom(outpost)
            assertEquals(listOf("Wharf"), dests.map { it.name }, "route to a missing room is dropped")
        }

        @Test
        fun `destinationsFrom is empty off a boat dock`() {
            assertTrue(BoatSystem(boatWorld()).destinationsFrom(inland).isEmpty())
        }

        @Test
        fun `originAt returns the current dock pin and null off a dock`() {
            val system = BoatSystem(boatWorld())
            val origin = system.originAt(harbor)
            assertEquals("Harbor", origin?.name)
            assertEquals(20.0, origin?.mapX)
            assertEquals(30.0, origin?.mapY)
            assertNull(system.originAt(inland), "non-dock room is not an origin")
        }
    }

    @Nested
    inner class Router {
        @Test
        fun `voyages requires a boat dock`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.roomId = inland
            h.drain()

            h.router.handle(sid, Command.Boat.List)

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("boat dock") }, "got=$errors")
        }

        @Test
        fun `voyages on a dock with no routes reports an empty dock`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.roomId = emptyDock
            h.drain()

            h.router.handle(sid, Command.Boat.List)

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("no routes", ignoreCase = true) }, "got=$infos")
        }

        @Test
        fun `voyages lists authored routes with fares`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = harbor
            me.gold = 500
            h.drain()

            h.router.handle(sid, Command.Boat.List)

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("Wharf") && it.contains("50 gold") }, "got=$infos")
            assertTrue(infos.any { it.contains("Pier") && it.contains("120 gold") }, "got=$infos")
        }

        @Test
        fun `sail charges the flat fare and moves the player`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = harbor
            me.gold = 500
            h.drain()

            h.router.handle(sid, Command.Boat.Travel("Pier"))
            h.drain()

            val after = h.players.get(sid)!!
            assertEquals(pier, after.roomId, "player should arrive at the destination")
            assertEquals(380L, after.gold, "flat fare of 120 deducted from 500")
        }

        @Test
        fun `sail resolves a numeric index in authored order`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = harbor
            me.gold = 500
            h.drain()

            // Authored order: 1=Wharf, 2=Pier.
            h.router.handle(sid, Command.Boat.Travel("1"))
            h.drain()

            assertEquals(wharf, h.players.get(sid)!!.roomId)
        }

        @Test
        fun `sail rejects insufficient gold without moving`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = harbor
            me.gold = 10
            h.drain()

            h.router.handle(sid, Command.Boat.Travel("Wharf"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("50 gold") }, "got=$errors")
            val after = h.players.get(sid)!!
            assertEquals(harbor, after.roomId)
            assertEquals(10L, after.gold)
        }

        @Test
        fun `sail rejects an unknown destination`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = harbor
            me.gold = 500
            h.drain()

            h.router.handle(sid, Command.Boat.Travel("Atlantis"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("doesn't sail") }, "got=$errors")
            assertEquals(harbor, h.players.get(sid)!!.roomId)
        }

        @Test
        fun `sail is blocked while in combat`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = harbor
            me.gold = 500
            // Put a hostile mob in the room and engage so isInCombat is true.
            h.mobs.upsert(MobState(MobId("coast:thug"), "a thug", harbor, hp = 10, maxHp = 10))
            assertNull(h.combat.startCombat(sid, "thug"))
            h.drain()

            h.router.handle(sid, Command.Boat.Travel("Wharf"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("battle", ignoreCase = true) }, "got=$errors")
            assertEquals(harbor, h.players.get(sid)!!.roomId)
            assertEquals(500L, h.players.get(sid)!!.gold)
        }
    }

    companion object {
        private val harbor = RoomId("coast:harbor")
        private val wharf = RoomId("coast:wharf")
        private val pier = RoomId("coast:pier")
        private val inland = RoomId("coast:inland")
        private val outpost = RoomId("coast:outpost")
        private val emptyDock = RoomId("coast:empty_dock")
        private val missing = RoomId("far:gone")

        /**
         * A small harbor network. Routes are authored per dock with flat fares; `harbor` sails to a
         * pinned `wharf` (50) and an unpinned `pier` (120). `outpost` includes a route to a room not
         * loaded here (`missing`) to cover the runtime drop. `inland` is not a dock; `emptyDock` is a
         * dock with no routes.
         */
        private fun boatWorld(): World {
            val noExits = emptyMap<Direction, RoomId>()
            val rooms = mapOf(
                harbor to Room(
                    harbor,
                    "Harbor",
                    "A busy harbor.",
                    noExits,
                    boatDock = true,
                    boatMapX = 20.0,
                    boatMapY = 30.0,
                    boatRoutes = listOf(BoatRoute(wharf, 50), BoatRoute(pier, 120)),
                ),
                wharf to Room(
                    wharf,
                    "Wharf",
                    "A salt-stained wharf.",
                    noExits,
                    boatDock = true,
                    boatMapX = 55.0,
                    boatMapY = 60.0,
                    boatRoutes = listOf(BoatRoute(harbor, 50)),
                ),
                // Pier is a dock but intentionally left unpinned (no boatMapX/Y) to cover list-only fallback.
                pier to Room(
                    pier,
                    "Pier",
                    "A weathered pier.",
                    noExits,
                    boatDock = true,
                    boatRoutes = listOf(BoatRoute(harbor, 120)),
                ),
                inland to Room(inland, "Inland Road", "A dusty road.", noExits),
                outpost to Room(
                    outpost,
                    "Outpost",
                    "A lonely outpost.",
                    noExits,
                    boatDock = true,
                    boatMapX = 10.0,
                    boatMapY = 10.0,
                    boatRoutes = listOf(BoatRoute(wharf, 10), BoatRoute(missing, 99)),
                ),
                emptyDock to Room(emptyDock, "Quiet Quay", "A quay with no boats.", noExits, boatDock = true),
            )
            return World(rooms = rooms, startRoom = harbor)
        }

        private fun harness(): CommandRouterHarness {
            val world = boatWorld()
            val players = buildTestPlayerRegistry(world.startRoom)
            return CommandRouterHarness.create(world = world, players = players)
        }
    }
}
