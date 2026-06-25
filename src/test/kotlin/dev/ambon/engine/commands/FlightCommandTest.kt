package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.FlightConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.FlightSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlightCommandTest {
    @Nested
    inner class Parser {
        @Test
        fun `flights parses to Flight List`() {
            assertEquals(Command.Flight.List, CommandParser.parse("flights"))
        }

        @Test
        fun `fly with destination parses to Travel`() {
            val result = CommandParser.parse("fly bastion")
            assertTrue(result is Command.Flight.Travel)
            assertEquals("bastion", (result as Command.Flight.Travel).destination)
        }

        @Test
        fun `fly with numeric index parses to Travel`() {
            val result = CommandParser.parse("fly 2")
            assertTrue(result is Command.Flight.Travel)
            assertEquals("2", (result as Command.Flight.Travel).destination)
        }

        @Test
        fun `fly without arg returns Invalid`() {
            assertTrue(CommandParser.parse("fly") is Command.Invalid)
        }

        @Test
        fun `flee still parses to Flee not flight`() {
            assertEquals(Command.Flee, CommandParser.parse("flee"))
        }
    }

    @Nested
    inner class System {
        @Test
        fun `costForDistance scales and clamps`() {
            val system = FlightSystem(
                players = buildTestPlayerRegistry(aerie),
                world = flightWorld(),
                outbound = LocalOutboundBus(),
                config = FlightConfig(baseCost = 10, costPerRoom = 5, minCost = 10, maxCost = 100, unreachableCost = 500),
            )
            assertEquals(10L, system.costForDistance(0), "floor applies at distance 0")
            assertEquals(20L, system.costForDistance(2))
            assertEquals(100L, system.costForDistance(1000), "ceiling clamps long hauls")
            assertEquals(500L, system.costForDistance(null), "unreachable uses fallback fare")
        }

        @Test
        fun `destinationsFrom excludes current room and non-flight rooms with distance pricing`() {
            val system = FlightSystem(
                players = buildTestPlayerRegistry(aerie),
                world = flightWorld(),
                outbound = LocalOutboundBus(),
                config = FlightConfig(baseCost = 10, costPerRoom = 5, minCost = 10, maxCost = 1000),
            )
            // Discovered everything, including a non-flight room (mid1) which must be ignored.
            val discovered = setOf(aerie.value, bastion.value, citadel.value, mid1.value)
            val dests = system.destinationsFrom(aerie, discovered)

            assertEquals(listOf("Bastion", "Citadel"), dests.map { it.name }, "current room + non-flight room excluded")
            val bastionDest = dests.first { it.name == "Bastion" }
            val citadelDest = dests.first { it.name == "Citadel" }
            assertEquals(2, bastionDest.distance)
            assertEquals(4, citadelDest.distance)
            // base 10 + 5/room: Bastion 2 hops -> 20, Citadel 4 hops -> 30.
            assertEquals(20L, bastionDest.cost)
            assertEquals(30L, citadelDest.cost)
        }

        @Test
        fun `destinationsFrom carries world-map pins and leaves unpinned roosts null`() {
            val system = FlightSystem(
                players = buildTestPlayerRegistry(aerie),
                world = flightWorld(),
                outbound = LocalOutboundBus(),
            )
            val discovered = setOf(aerie.value, bastion.value, citadel.value)
            val dests = system.destinationsFrom(aerie, discovered)

            val bastionDest = dests.first { it.name == "Bastion" }
            val citadelDest = dests.first { it.name == "Citadel" }
            assertEquals(55.0, bastionDest.mapX)
            assertEquals(60.0, bastionDest.mapY)
            assertNull(citadelDest.mapX, "unpinned roost has no map x")
            assertNull(citadelDest.mapY, "unpinned roost has no map y")
        }

        @Test
        fun `originAt returns the current flight master pin and null off a flight master`() {
            val system = FlightSystem(
                players = buildTestPlayerRegistry(aerie),
                world = flightWorld(),
                outbound = LocalOutboundBus(),
            )
            val origin = system.originAt(aerie)
            assertEquals("Aerie", origin?.name)
            assertEquals(20.0, origin?.mapX)
            assertEquals(30.0, origin?.mapY)
            assertNull(system.originAt(mid1), "non-flight room is not an origin")
        }

        @Test
        fun `onRoomVisited records the flight point persists and notifies once`() = runTest {
            val players = buildTestPlayerRegistry(aerie)
            val outbound = LocalOutboundBus()
            var dirtyCount = 0
            val system = FlightSystem(
                players = players,
                world = flightWorld(),
                outbound = outbound,
                config = FlightConfig(),
                markVitalsDirty = { dirtyCount++ },
            )
            val sid = SessionId(1)
            players.loginOrFail(sid, "Pilot")
            players.get(sid)!!.roomId = bastion
            outbound.drainAll()

            system.onRoomVisited(sid)
            assertTrue(bastion.value in players.get(sid)!!.discoveredFlightPoints)
            assertEquals(1, dirtyCount, "first visit persists")
            val info = outbound.drainAll().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(info.any { it.contains("flight point", ignoreCase = true) }, "got=$info")

            // Second visit is a no-op: no extra persist, no duplicate message.
            system.onRoomVisited(sid)
            assertEquals(1, dirtyCount, "already-discovered visit must not re-persist")
            assertTrue(outbound.drainAll().filterIsInstance<OutboundEvent.SendInfo>().isEmpty())
        }

        @Test
        fun `onRoomVisited ignores non-flight rooms`() = runTest {
            val players = buildTestPlayerRegistry(aerie)
            val system = FlightSystem(
                players = players,
                world = flightWorld(),
                outbound = LocalOutboundBus(),
                config = FlightConfig(),
            )
            val sid = SessionId(1)
            players.loginOrFail(sid, "Pilot")
            players.get(sid)!!.roomId = mid1
            system.onRoomVisited(sid)
            assertTrue(players.get(sid)!!.discoveredFlightPoints.isEmpty())
        }
    }

    @Nested
    inner class Router {
        @Test
        fun `flights requires a flight master room`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.roomId = mid1
            h.drain()

            h.router.handle(sid, Command.Flight.List)

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("flight master") }, "got=$errors")
        }

        @Test
        fun `flights with nothing discovered prompts exploration`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.roomId = aerie
            h.drain()

            h.router.handle(sid, Command.Flight.List)

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("haven't discovered") }, "got=$infos")
        }

        @Test
        fun `flights lists discovered destinations with fares`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = aerie
            me.gold = 500
            me.discoveredFlightPoints = mutableSetOf(aerie.value, bastion.value, citadel.value)
            h.drain()

            h.router.handle(sid, Command.Flight.List)

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("Bastion") && it.contains("20 gold") }, "got=$infos")
            assertTrue(infos.any { it.contains("Citadel") && it.contains("30 gold") }, "got=$infos")
            assertFalse(infos.any { it.contains("Aerie") }, "current room must not be listed: $infos")
        }

        @Test
        fun `fly charges distance-scaled fare and moves the player`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = aerie
            me.gold = 500
            me.discoveredFlightPoints = mutableSetOf(aerie.value, bastion.value, citadel.value)
            h.drain()

            h.router.handle(sid, Command.Flight.Travel("Citadel"))
            h.drain()

            val after = h.players.get(sid)!!
            assertEquals(citadel, after.roomId, "player should arrive at the destination")
            assertEquals(470L, after.gold, "fare of 30 deducted from 500")
        }

        @Test
        fun `fly resolves a numeric index from the list`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = aerie
            me.gold = 500
            me.discoveredFlightPoints = mutableSetOf(aerie.value, bastion.value, citadel.value)
            h.drain()

            // Sorted by zone then name: 1=Bastion, 2=Citadel.
            h.router.handle(sid, Command.Flight.Travel("1"))
            h.drain()

            assertEquals(bastion, h.players.get(sid)!!.roomId)
        }

        @Test
        fun `fly rejects insufficient gold without moving`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = aerie
            me.gold = 5
            me.discoveredFlightPoints = mutableSetOf(aerie.value, bastion.value, citadel.value)
            h.drain()

            h.router.handle(sid, Command.Flight.Travel("Bastion"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("20 gold") }, "got=$errors")
            val after = h.players.get(sid)!!
            assertEquals(aerie, after.roomId)
            assertEquals(5L, after.gold)
        }

        @Test
        fun `fly rejects an undiscovered or unknown destination`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = aerie
            me.gold = 500
            // Citadel not discovered.
            me.discoveredFlightPoints = mutableSetOf(aerie.value, bastion.value)
            h.drain()

            h.router.handle(sid, Command.Flight.Travel("Citadel"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("doesn't recognize") }, "got=$errors")
            assertEquals(aerie, h.players.get(sid)!!.roomId)
        }

        @Test
        fun `fly is blocked while in combat`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.roomId = aerie
            me.gold = 500
            me.discoveredFlightPoints = mutableSetOf(aerie.value, bastion.value, citadel.value)
            // Put a hostile mob in the room and engage so isInCombat is true.
            h.mobs.upsert(MobState(MobId("flight:thug"), "a thug", aerie, hp = 10, maxHp = 10))
            assertNull(h.combat.startCombat(sid, "thug"))
            h.drain()

            h.router.handle(sid, Command.Flight.Travel("Bastion"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("battle", ignoreCase = true) }, "got=$errors")
            assertEquals(aerie, h.players.get(sid)!!.roomId)
            assertEquals(500L, h.players.get(sid)!!.gold)
        }
    }

    companion object {
        private val aerie = RoomId("sky:aerie")
        private val mid1 = RoomId("sky:mid1")
        private val bastion = RoomId("sky:bastion")
        private val mid2 = RoomId("sky:mid2")
        private val citadel = RoomId("sky:citadel")

        /**
         * A linear chain aerie =e= mid1 =e= bastion =e= mid2 =e= citadel, with flight masters at
         * the three named hubs. Hop distances from aerie: bastion 2, citadel 4 — so distance-scaled
         * fares differ between destinations.
         */
        private fun flightWorld(): World {
            val rooms = mapOf(
                aerie to Room(
                    aerie,
                    "Aerie",
                    "A high roost.",
                    mapOf(Direction.EAST to mid1),
                    flightMaster = true,
                    flightMapX = 20.0,
                    flightMapY = 30.0,
                ),
                mid1 to Room(mid1, "Windy Pass", "A pass.", mapOf(Direction.WEST to aerie, Direction.EAST to bastion)),
                bastion to Room(
                    bastion,
                    "Bastion",
                    "A fortified roost.",
                    mapOf(Direction.WEST to mid1, Direction.EAST to mid2),
                    flightMaster = true,
                    flightMapX = 55.0,
                    flightMapY = 60.0,
                ),
                mid2 to Room(mid2, "Cloud Bridge", "A bridge.", mapOf(Direction.WEST to bastion, Direction.EAST to citadel)),
                // Citadel is intentionally left unpinned (no flightMapX/Y) to cover the list-only fallback.
                citadel to Room(citadel, "Citadel", "A distant roost.", mapOf(Direction.WEST to mid2), flightMaster = true),
            )
            return World(rooms = rooms, startRoom = aerie)
        }

        private fun harness(): CommandRouterHarness {
            val world = flightWorld()
            val players = buildTestPlayerRegistry(world.startRoom)
            return CommandRouterHarness.create(
                world = world,
                players = players,
                flightConfig = FlightConfig(baseCost = 10, costPerRoom = 5, minCost = 10, maxCost = 1000),
            )
        }
    }
}
