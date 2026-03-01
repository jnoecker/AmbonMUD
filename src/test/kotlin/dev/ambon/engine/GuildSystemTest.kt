package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.guild.GuildRank
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
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

class GuildSystemTest {
    private val roomId = RoomId("zone:room")

    private fun setup(): TestHarness {
        val items = ItemRegistry()
        val playerRepo = InMemoryPlayerRepository()
        val players = buildTestPlayerRegistry(roomId, playerRepo, items)
        val outbound = LocalOutboundBus()
        val clock = MutableClock(0L)
        val guildRepo = InMemoryGuildRepository()
        val guild =
            GuildSystem(
                players = players,
                guildRepo = guildRepo,
                playerRepo = playerRepo,
                outbound = outbound,
                clock = clock,
                maxSize = 5,
                inviteTimeoutMs = 60_000L,
            )
        return TestHarness(players, playerRepo, outbound, clock, guild, guildRepo, items)
    }

    private data class TestHarness(
        val players: PlayerRegistry,
        val playerRepo: InMemoryPlayerRepository,
        val outbound: LocalOutboundBus,
        val clock: MutableClock,
        val guild: GuildSystem,
        val guildRepo: InMemoryGuildRepository,
        val items: ItemRegistry,
    )

    @Test
    fun `create guild success`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")
            h.outbound.drainAll()

            val err = h.guild.create(sid, "Shadowblade", "SB")
            assertNull(err)

            val ps = h.players.get(sid)!!
            assertEquals("shadowblade", ps.guildId)
            assertEquals(GuildRank.LEADER, ps.guildRank)
            assertEquals("SB", ps.guildTag)

            val record = h.guildRepo.findById("shadowblade")
            assertNotNull(record)
            assertEquals("Shadowblade", record!!.name)
            assertEquals("SB", record.tag)

            val events = h.outbound.drainAll()
            assertTrue(events.any { it is OutboundEvent.SendInfo && it.text.contains("Shadowblade") })
        }

    @Test
    fun `create guild name too short returns error`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")

            val err = h.guild.create(sid, "AB", "SB")
            assertNotNull(err)
            assertTrue(err!!.contains("3–32"))
        }

    @Test
    fun `create guild tag too long returns error`() =
        runTest {
            val h = setup()
            val sid = SessionId(1L)
            h.players.loginOrFail(sid, "Alice")

            val err = h.guild.create(sid, "Shadowblade", "TOOLONGTAG")
            assertNotNull(err)
            assertTrue(err!!.contains("2–5"))
        }

    @Test
    fun `create guild duplicate name returns error`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")

            h.guild.create(sid1, "Shadowblade", "SB")

            val err = h.guild.create(sid2, "shadowblade", "XX")
            assertNotNull(err)
            assertTrue(err!!.contains("already exists"))
        }

    @Test
    fun `create guild with name that produces same id returns error`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")

            h.guild.create(sid1, "Shadow Blade", "SB")

            val err = h.guild.create(sid2, "Shadow-Blade", "XX")
            assertNotNull(err)
            assertTrue(err!!.contains("conflicting name"))
        }

    @Test
    fun `invite and accept joins guild`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.guild.create(sid1, "Shadowblade", "SB")
            h.outbound.drainAll()

            val inviteErr = h.guild.invite(sid1, "Bob")
            assertNull(inviteErr)

            val acceptErr = h.guild.accept(sid2)
            assertNull(acceptErr)

            val bobPs = h.players.get(sid2)!!
            assertEquals("shadowblade", bobPs.guildId)
            assertEquals(GuildRank.MEMBER, bobPs.guildRank)
            assertEquals("SB", bobPs.guildTag)
        }

    @Test
    fun `invite expires after timeout`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.guild.create(sid1, "Shadowblade", "SB")

            h.guild.invite(sid1, "Bob")
            h.clock.advance(61_000L)

            val acceptErr = h.guild.accept(sid2)
            assertNotNull(acceptErr)
            assertTrue(acceptErr!!.contains("expired"))
        }

    @Test
    fun `member can leave guild`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.guild.create(sid1, "Shadowblade", "SB")
            h.guild.invite(sid1, "Bob")
            h.guild.accept(sid2)
            h.outbound.drainAll()

            val leaveErr = h.guild.leave(sid2)
            assertNull(leaveErr)

            val bobPs = h.players.get(sid2)!!
            assertNull(bobPs.guildId)
            assertNull(bobPs.guildRank)
            assertNull(bobPs.guildTag)
        }

    @Test
    fun `leader cannot leave must disband`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            h.players.loginOrFail(sid1, "Alice")
            h.guild.create(sid1, "Shadowblade", "SB")

            val leaveErr = h.guild.leave(sid1)
            assertNotNull(leaveErr)
            assertTrue(leaveErr!!.contains("disband"))
        }

    @Test
    fun `disband removes all members`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.guild.create(sid1, "Shadowblade", "SB")
            h.guild.invite(sid1, "Bob")
            h.guild.accept(sid2)
            h.outbound.drainAll()

            val disbandErr = h.guild.disband(sid1)
            assertNull(disbandErr)

            val alicePs = h.players.get(sid1)!!
            assertNull(alicePs.guildId)
            assertNull(alicePs.guildRank)

            val bobPs = h.players.get(sid2)!!
            assertNull(bobPs.guildId)
            assertNull(bobPs.guildRank)

            assertNull(h.guildRepo.findById("shadowblade"))
        }

    @Test
    fun `kick removes member`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.guild.create(sid1, "Shadowblade", "SB")
            h.guild.invite(sid1, "Bob")
            h.guild.accept(sid2)
            h.outbound.drainAll()

            val kickErr = h.guild.kick(sid1, "Bob")
            assertNull(kickErr)

            val bobPs = h.players.get(sid2)!!
            assertNull(bobPs.guildId)

            val guildRecord = h.guildRepo.findById("shadowblade")!!
            val bobPid = h.players.get(sid2)!!.playerId!!
            assertTrue(bobPid !in guildRecord.members)
        }

    @Test
    fun `promote member to officer`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.guild.create(sid1, "Shadowblade", "SB")
            h.guild.invite(sid1, "Bob")
            h.guild.accept(sid2)
            h.outbound.drainAll()

            val promoteErr = h.guild.promote(sid1, "Bob")
            assertNull(promoteErr)

            val bobPs = h.players.get(sid2)!!
            assertEquals(GuildRank.OFFICER, bobPs.guildRank)
        }

    @Test
    fun `demote officer to member`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.guild.create(sid1, "Shadowblade", "SB")
            h.guild.invite(sid1, "Bob")
            h.guild.accept(sid2)
            h.guild.promote(sid1, "Bob")
            h.outbound.drainAll()

            val demoteErr = h.guild.demote(sid1, "Bob")
            assertNull(demoteErr)

            val bobPs = h.players.get(sid2)!!
            assertEquals(GuildRank.MEMBER, bobPs.guildRank)
        }

    @Test
    fun `gchat sends to all online members`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.guild.create(sid1, "Shadowblade", "SB")
            h.guild.invite(sid1, "Bob")
            h.guild.accept(sid2)
            h.outbound.drainAll()

            val gchatErr = h.guild.gchat(sid1, "Hello guild!")
            assertNull(gchatErr)

            val events = h.outbound.drainAll()
            val textEvents = events.filterIsInstance<OutboundEvent.SendInfo>()
            val aliceGotIt = textEvents.any { it.sessionId == sid1 && it.text.contains("Hello guild!") }
            val bobGotIt = textEvents.any { it.sessionId == sid2 && it.text.contains("Hello guild!") }
            assertTrue(aliceGotIt)
            assertTrue(bobGotIt)
        }

    @Test
    fun `set motd stores message`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            h.players.loginOrFail(sid1, "Alice")
            h.guild.create(sid1, "Shadowblade", "SB")

            val motdErr = h.guild.setMotd(sid1, "Welcome!")
            assertNull(motdErr)

            val record = h.guildRepo.findById("shadowblade")!!
            assertEquals("Welcome!", record.motd)
        }

    @Test
    fun `disconnect cleans up pending invite`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.guild.create(sid1, "Shadowblade", "SB")
            h.guild.invite(sid1, "Bob")

            h.guild.onPlayerDisconnected(sid2)

            val acceptErr = h.guild.accept(sid2)
            assertNotNull(acceptErr)
            assertTrue(acceptErr!!.contains("no pending"))
        }

    @Test
    fun `onPlayerLogin populates guild state`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.guild.create(sid1, "Shadowblade", "SB")
            h.guild.invite(sid1, "Bob")
            h.guild.accept(sid2)

            // Simulate: Bob re-logs in. His record has guildId set. onPlayerLogin should populate rank/tag.
            val bobPs = h.players.get(sid2)!!
            // Manually reset ephemeral fields (as if Bob just re-loaded from DB)
            bobPs.guildRank = null
            bobPs.guildTag = null

            h.guild.onPlayerLogin(sid2)

            assertEquals(GuildRank.MEMBER, bobPs.guildRank)
            assertEquals("SB", bobPs.guildTag)
        }
}
