package dev.ambon.engine.commands

import dev.ambon.config.StylistConfig
import dev.ambon.domain.RaceDef
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.RaceRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.buildTestPlayerRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StylistCommandTest {
    @Nested
    inner class Parser {
        @Test
        fun `stylist parses to Stylist List`() {
            assertEquals(Command.Stylist.List, CommandParser.parse("stylist"))
        }

        @Test
        fun `changerace parses to ChangeRace with id`() {
            val result = CommandParser.parse("changerace elf")
            assertTrue(result is Command.Stylist.ChangeRace)
            assertEquals("elf", (result as Command.Stylist.ChangeRace).raceId)
        }

        @Test
        fun `changerace without arg returns Invalid`() {
            assertTrue(CommandParser.parse("changerace") is Command.Invalid)
        }
    }

    companion object {
        private val stylistRoom = RoomId("test:stylist")
        private val lobbyRoom = RoomId("test:lobby")

        private fun buildRaceRegistry(): RaceRegistry {
            val registry = RaceRegistry()
            registry.register(
                RaceDef(
                    id = "HUMAN",
                    displayName = "Human",
                    statMods = StatMap.of("STR" to 1, "CHA" to 1),
                ),
            )
            registry.register(
                RaceDef(
                    id = "ELF",
                    displayName = "Elf",
                    statMods = StatMap.of("DEX" to 2, "INT" to 1, "CON" to -1),
                ),
            )
            return registry
        }
    }

    private fun stylistWorld(): World {
        val rooms = mapOf(
            stylistRoom to Room(
                id = stylistRoom,
                title = "Stylist Salon",
                description = "A salon.",
                exits = mapOf(dev.ambon.domain.world.Direction.SOUTH to lobbyRoom),
                stylist = true,
            ),
            lobbyRoom to Room(
                id = lobbyRoom,
                title = "Lobby",
                description = "A lobby.",
                exits = mapOf(dev.ambon.domain.world.Direction.NORTH to stylistRoom),
            ),
        )
        return World(rooms = rooms, startRoom = stylistRoom)
    }

    private fun harness(fee: Long = 500): CommandRouterHarness {
        val world = stylistWorld()
        val players = buildTestPlayerRegistry(world.startRoom)
        return CommandRouterHarness.create(
            world = world,
            players = players,
            stylistConfig = StylistConfig(feeGold = fee),
            raceRegistry = buildRaceRegistry(),
        )
    }

    @Nested
    inner class Router {
        @Test
        fun `stylist list requires stylist room`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.roomId = lobbyRoom
            h.drain()

            h.router.handle(sid, Command.Stylist.List)

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("stylist") }, "got=$errors")
        }

        @Test
        fun `stylist list shows races fee and current`() = runTest {
            val h = harness(fee = 500)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Stylist.List)

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("500 gold") }, "fee not shown: $infos")
            assertTrue(infos.any { it.contains("HUMAN") }, "HUMAN not listed: $infos")
            assertTrue(infos.any { it.contains("ELF") }, "ELF not listed: $infos")
        }

        @Test
        fun `changerace charges fee and updates race`() = runTest {
            val h = harness(fee = 500)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.gold = 1000L
            me.race = "HUMAN"
            me.stats = StatMap.of("STR" to 11, "DEX" to 10, "CON" to 10, "INT" to 10, "WIS" to 10, "CHA" to 11)
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("ELF"))

            h.drain()
            val after = h.players.get(sid)!!
            assertEquals("ELF", after.race)
            assertEquals(500L, after.gold)
            // STR: 11 (base 10 + HUMAN +1) → remove +1 → 10; ELF has no STR mod → stays 10
            assertEquals(10, after.stats["STR"])
            // CHA: 11 → remove HUMAN +1 → 10; ELF has no CHA mod → stays 10
            assertEquals(10, after.stats["CHA"])
            // DEX: 10 → ELF +2 → 12
            assertEquals(12, after.stats["DEX"])
            // CON: 10 → ELF -1 → 9
            assertEquals(9, after.stats["CON"])
            // INT: 10 → ELF +1 → 11
            assertEquals(11, after.stats["INT"])
        }

        @Test
        fun `changerace rejects insufficient gold`() = runTest {
            val h = harness(fee = 500)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.gold = 100L
            me.race = "HUMAN"
            val beforeStats = me.stats
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("ELF"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("500") }, "got=$errors")
            val after = h.players.get(sid)!!
            assertEquals("HUMAN", after.race)
            assertEquals(100L, after.gold)
            assertEquals(beforeStats, after.stats)
        }

        @Test
        fun `changerace rejects unknown race`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.gold = 1000L
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("DRAGON"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("DRAGON") || it.contains("No such race") }, "got=$errors")
            assertEquals(1000L, h.players.get(sid)!!.gold)
        }

        @Test
        fun `changerace rejects current race`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.gold = 1000L
            me.race = "HUMAN"
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("HUMAN"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("already") }, "got=$errors")
            assertEquals(1000L, h.players.get(sid)!!.gold)
        }

        @Test
        fun `changerace recomputes maxHp after stat change`() = runTest {
            val h = harness(fee = 0)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.race = "HUMAN"
            me.stats = StatMap.of("STR" to 10, "DEX" to 10, "CON" to 20, "INT" to 10, "WIS" to 10, "CHA" to 10)
            me.level = 10
            h.progression.applyLevelStats(me, 10)
            val hpBefore = me.maxHp
            h.drain()

            // Switch HUMAN → ELF: CON -1 will drop the scaling stat
            h.router.handle(sid, Command.Stylist.ChangeRace("ELF"))
            h.drain()

            val after = h.players.get(sid)!!
            assertEquals("ELF", after.race)
            assertEquals(19, after.stats["CON"])
            // CON dropped, so maxHp scaling should decrease (or at least differ)
            assertNotEquals(hpBefore, after.maxHp)
            assertTrue(after.hp <= after.maxHp)
        }
    }
}
