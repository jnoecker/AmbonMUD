package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.guild.GuildRank
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GuildSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryGuildRepository
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.TestWorlds
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandRouterGuildTest {
    private val roomId = RoomId("test:start")

    private data class Harness(
        val players: dev.ambon.engine.PlayerRegistry,
        val outbound: LocalOutboundBus,
        val clock: MutableClock,
        val router: CommandRouter,
    )

    private fun setup(withGuildSystem: Boolean = true): Harness {
        val items = ItemRegistry()
        val playerRepo = InMemoryPlayerRepository()
        val players = buildTestPlayerRegistry(roomId, playerRepo, items)
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val clock = MutableClock(0L)
        val world = TestWorlds.testWorld
        val combat = CombatSystem(players, mobs, items, outbound)
        val guildSystem =
            if (withGuildSystem) {
                GuildSystem(
                    players = players,
                    guildRepo = InMemoryGuildRepository(),
                    outbound = outbound,
                    clock = clock,
                )
            } else {
                null
            }
        val router =
            buildTestRouter(
                world = world,
                players = players,
                mobs = mobs,
                items = items,
                combat = combat,
                outbound = outbound,
                guildSystem = guildSystem,
            )
        return Harness(players, outbound, clock, router)
    }

    @Test
    fun `guild command without guild system returns error`() =
        runTest {
            val h = setup(withGuildSystem = false)
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.outbound.drainAll()

            h.router.handle(sid, Command.Guild.Info)

            val events = h.outbound.drainAll()
            assertTrue(
                events.any { it is OutboundEvent.SendError && it.sessionId == sid },
                "Expected SendError when guild system is absent. got=$events",
            )
        }

    @Test
    fun `gchat command without guild system returns error`() =
        runTest {
            val h = setup(withGuildSystem = false)
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.outbound.drainAll()

            h.router.handle(sid, Command.Gchat("hello"))

            val events = h.outbound.drainAll()
            assertTrue(
                events.any { it is OutboundEvent.SendError && it.sessionId == sid },
                "Expected SendError when guild system is absent. got=$events",
            )
        }

    @Test
    fun `guild create routes to guild system and sets player state`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.outbound.drainAll()

            h.router.handle(sid, Command.Guild.Create("Shadowblade", "SB"))

            val ps = h.players.get(sid)!!
            assertEquals("shadowblade", ps.guildId)
            assertEquals(GuildRank.LEADER, ps.guildRank)

            val events = h.outbound.drainAll()
            assertTrue(
                events.any { it is OutboundEvent.SendInfo && it.sessionId == sid && it.text.contains("Shadowblade") },
                "Expected success message. got=$events",
            )
        }

    @Test
    fun `guild create with invalid name surfaces error`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.outbound.drainAll()

            h.router.handle(sid, Command.Guild.Create("AB", "SB")) // name too short

            val events = h.outbound.drainAll()
            assertTrue(
                events.any { it is OutboundEvent.SendError && it.sessionId == sid },
                "Expected SendError for invalid name. got=$events",
            )
        }

    @Test
    fun `guild invite and accept flow via router`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.router.handle(sid1, Command.Guild.Create("Shadowblade", "SB"))
            h.outbound.drainAll()

            h.router.handle(sid1, Command.Guild.Invite("Bob"))
            h.router.handle(sid2, Command.Guild.Accept)

            val bobPs = h.players.get(sid2)!!
            assertEquals("shadowblade", bobPs.guildId)
            assertEquals(GuildRank.MEMBER, bobPs.guildRank)
        }

    @Test
    fun `guild leave removes member`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.router.handle(sid1, Command.Guild.Create("Shadowblade", "SB"))
            h.router.handle(sid1, Command.Guild.Invite("Bob"))
            h.router.handle(sid2, Command.Guild.Accept)
            h.outbound.drainAll()

            h.router.handle(sid2, Command.Guild.Leave)

            assertNull(h.players.get(sid2)!!.guildId)
        }

    @Test
    fun `guild kick removes member`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.router.handle(sid1, Command.Guild.Create("Shadowblade", "SB"))
            h.router.handle(sid1, Command.Guild.Invite("Bob"))
            h.router.handle(sid2, Command.Guild.Accept)
            h.outbound.drainAll()

            h.router.handle(sid1, Command.Guild.Kick("Bob"))

            assertNull(h.players.get(sid2)!!.guildId)
        }

    @Test
    fun `guild promote and demote change member rank`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.router.handle(sid1, Command.Guild.Create("Shadowblade", "SB"))
            h.router.handle(sid1, Command.Guild.Invite("Bob"))
            h.router.handle(sid2, Command.Guild.Accept)
            h.outbound.drainAll()

            h.router.handle(sid1, Command.Guild.Promote("Bob"))
            assertEquals(GuildRank.OFFICER, h.players.get(sid2)!!.guildRank)

            h.router.handle(sid1, Command.Guild.Demote("Bob"))
            assertEquals(GuildRank.MEMBER, h.players.get(sid2)!!.guildRank)
        }

    @Test
    fun `guild disband removes all members`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            h.players.loginOrFail(sid1, "Alice")
            h.router.handle(sid1, Command.Guild.Create("Shadowblade", "SB"))
            h.outbound.drainAll()

            h.router.handle(sid1, Command.Guild.Disband)

            assertNull(h.players.get(sid1)!!.guildId)
        }

    @Test
    fun `guild motd updates message of the day`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.router.handle(sid, Command.Guild.Create("Shadowblade", "SB"))
            h.outbound.drainAll()

            h.router.handle(sid, Command.Guild.Motd("Welcome to Shadowblade!"))

            val events = h.outbound.drainAll()
            assertTrue(
                events.any { it is OutboundEvent.SendInfo && it.sessionId == sid && it.text.contains("MOTD") },
                "Expected MOTD confirmation. got=$events",
            )
        }

    @Test
    fun `guild roster shows member list`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.router.handle(sid, Command.Guild.Create("Shadowblade", "SB"))
            h.outbound.drainAll()

            h.router.handle(sid, Command.Guild.Roster)

            val events = h.outbound.drainAll()
            assertTrue(
                events.any { it is OutboundEvent.SendInfo && it.sessionId == sid && it.text.contains("Shadowblade") },
                "Expected roster with guild name. got=$events",
            )
        }

    @Test
    fun `guild info shows guild details`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.router.handle(sid, Command.Guild.Create("Shadowblade", "SB"))
            h.outbound.drainAll()

            h.router.handle(sid, Command.Guild.Info)

            val events = h.outbound.drainAll()
            assertTrue(
                events.any { it is OutboundEvent.SendInfo && it.sessionId == sid && it.text.contains("Shadowblade") },
                "Expected info with guild name. got=$events",
            )
        }

    @Test
    fun `gchat routes message to all guild members`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.router.handle(sid1, Command.Guild.Create("Shadowblade", "SB"))
            h.router.handle(sid1, Command.Guild.Invite("Bob"))
            h.router.handle(sid2, Command.Guild.Accept)
            h.outbound.drainAll()

            h.router.handle(sid1, Command.Gchat("Hello guild!"))

            val events = h.outbound.drainAll()
            assertTrue(
                events.any { it is OutboundEvent.SendInfo && it.sessionId == sid1 && it.text.contains("Hello guild!") },
                "Alice should receive gchat. got=$events",
            )
            assertTrue(
                events.any { it is OutboundEvent.SendInfo && it.sessionId == sid2 && it.text.contains("Hello guild!") },
                "Bob should receive gchat. got=$events",
            )
        }
}
