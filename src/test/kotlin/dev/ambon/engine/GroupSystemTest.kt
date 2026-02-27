package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
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

class GroupSystemTest {
    private val roomId = RoomId("zone:room")
    private val room2 = RoomId("zone:room2")

    private fun setup(): TestHarness {
        val items = ItemRegistry()
        val players = buildTestPlayerRegistry(roomId, InMemoryPlayerRepository(), items)
        val outbound = LocalOutboundBus()
        val clock = MutableClock(0L)
        val group =
            GroupSystem(
                players = players,
                outbound = outbound,
                clock = clock,
                maxGroupSize = 5,
                inviteTimeoutMs = 60_000L,
            )
        return TestHarness(players, outbound, clock, group, items)
    }

    private data class TestHarness(
        val players: PlayerRegistry,
        val outbound: LocalOutboundBus,
        val clock: MutableClock,
        val group: GroupSystem,
        val items: ItemRegistry,
    )

    @Test
    fun `invite and accept creates group`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.outbound.drainAll()

            // Invite
            val inviteErr = h.group.invite(sid1, "Bob")
            assertNull(inviteErr)

            // Accept
            val acceptErr = h.group.accept(sid2)
            assertNull(acceptErr)

            // Verify group state
            assertTrue(h.group.isGrouped(sid1))
            assertTrue(h.group.isGrouped(sid2))
            assertTrue(h.group.isLeader(sid1))
            assertFalse(h.group.isLeader(sid2))
            assertEquals(listOf(sid1, sid2), h.group.groupMembers(sid1))
            assertEquals(listOf(sid1, sid2), h.group.groupMembers(sid2))
        }

    @Test
    fun `invite self fails`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            h.players.loginOrFail(sid1, "Alice")

            val err = h.group.invite(sid1, "Alice")
            assertNotNull(err)
            assertTrue(err!!.contains("yourself"))
        }

    @Test
    fun `invite target not in room fails`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")

            // Move Bob to a different room
            h.players.moveTo(sid2, room2)

            val err = h.group.invite(sid1, "Bob")
            assertNotNull(err)
            assertTrue(err!!.contains("not in the same room"))
        }

    @Test
    fun `invite target already in group fails`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            val sid3 = SessionId(3L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.players.loginOrFail(sid3, "Charlie")

            // Create group with Alice and Bob
            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)

            // Charlie tries to invite Bob
            val err = h.group.invite(sid3, "Bob")
            assertNotNull(err)
            assertTrue(err!!.contains("already in a group"))
        }

    @Test
    fun `accept without invite fails`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            h.players.loginOrFail(sid1, "Alice")

            val err = h.group.accept(sid1)
            assertNotNull(err)
            assertTrue(err!!.contains("no pending"))
        }

    @Test
    fun `leave dissolves two-member group`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)

            val err = h.group.leave(sid2)
            assertNull(err)

            // Both should be ungrouped
            assertFalse(h.group.isGrouped(sid1))
            assertFalse(h.group.isGrouped(sid2))
        }

    @Test
    fun `leave transfers leadership`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            val sid3 = SessionId(3L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.players.loginOrFail(sid3, "Charlie")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)
            h.group.invite(sid1, "Charlie")
            h.group.accept(sid3)

            // Leader leaves
            val err = h.group.leave(sid1)
            assertNull(err)

            assertFalse(h.group.isGrouped(sid1))
            assertTrue(h.group.isGrouped(sid2))
            assertTrue(h.group.isGrouped(sid3))
            assertTrue(h.group.isLeader(sid2))
        }

    @Test
    fun `kick by non-leader fails`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            val sid3 = SessionId(3L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.players.loginOrFail(sid3, "Charlie")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)
            h.group.invite(sid1, "Charlie")
            h.group.accept(sid3)

            // Bob (non-leader) tries to kick Charlie
            val err = h.group.kick(sid2, "Charlie")
            assertNotNull(err)
            assertTrue(err!!.contains("leader"))
        }

    @Test
    fun `kick removes member`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            val sid3 = SessionId(3L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.players.loginOrFail(sid3, "Charlie")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)
            h.group.invite(sid1, "Charlie")
            h.group.accept(sid3)

            val err = h.group.kick(sid1, "Bob")
            assertNull(err)

            assertFalse(h.group.isGrouped(sid2))
            assertTrue(h.group.isGrouped(sid1))
            assertTrue(h.group.isGrouped(sid3))
        }

    @Test
    fun `gtell broadcasts to all group members`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)
            h.outbound.drainAll()

            val err = h.group.gtell(sid1, "Hello group!")
            assertNull(err)

            val events = h.outbound.drainAll()
            val textEvents = events.filterIsInstance<OutboundEvent.SendText>()
            assertTrue(textEvents.any { it.sessionId == sid1 && it.text.contains("Hello group!") })
            assertTrue(textEvents.any { it.sessionId == sid2 && it.text.contains("Hello group!") })
        }

    @Test
    fun `gtell without group fails`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            h.players.loginOrFail(sid1, "Alice")

            val err = h.group.gtell(sid1, "Hello?")
            assertNotNull(err)
            assertTrue(err!!.contains("not in a group"))
        }

    @Test
    fun `disconnected player removed from group`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            val sid3 = SessionId(3L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.players.loginOrFail(sid3, "Charlie")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)
            h.group.invite(sid1, "Charlie")
            h.group.accept(sid3)

            h.group.onPlayerDisconnected(sid2)

            assertFalse(h.group.isGrouped(sid2))
            assertTrue(h.group.isGrouped(sid1))
            assertTrue(h.group.isGrouped(sid3))
        }

    @Test
    fun `disconnect leader of two-member group dissolves it`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)

            h.group.onPlayerDisconnected(sid1)

            assertFalse(h.group.isGrouped(sid1))
            assertFalse(h.group.isGrouped(sid2))
        }

    @Test
    fun `max group size enforced`() =
        runTest {
            val items = ItemRegistry()
            val players = buildTestPlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val outbound = LocalOutboundBus()
            val clock = MutableClock(0L)
            val group =
                GroupSystem(
                    players = players,
                    outbound = outbound,
                    clock = clock,
                    maxGroupSize = 2,
                    inviteTimeoutMs = 60_000L,
                )

            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            val sid3 = SessionId(3L)
            players.loginOrFail(sid1, "Alice")
            players.loginOrFail(sid2, "Bob")
            players.loginOrFail(sid3, "Charlie")

            group.invite(sid1, "Bob")
            group.accept(sid2)

            // Group is now full (2/2)
            val err = group.invite(sid1, "Charlie")
            assertNotNull(err)
            assertTrue(err!!.contains("full"))
        }

    @Test
    fun `invite timeout expires`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")

            h.group.invite(sid1, "Bob")

            // Advance clock past invite timeout
            h.clock.advance(61_000L)

            val err = h.group.accept(sid2)
            assertNotNull(err)
            assertTrue(err!!.contains("no pending"))
        }

    @Test
    fun `list shows group members`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)
            h.outbound.drainAll()

            h.group.list(sid1)

            val events = h.outbound.drainAll()
            val infoEvents = events.filterIsInstance<OutboundEvent.SendInfo>()
            assertTrue(infoEvents.any { it.text.contains("Alice") && it.text.contains("leader") })
            assertTrue(infoEvents.any { it.text.contains("Bob") })
        }

    @Test
    fun `non-leader cannot invite`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            val sid3 = SessionId(3L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.players.loginOrFail(sid3, "Charlie")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)

            // Bob (non-leader) tries to invite Charlie
            val err = h.group.invite(sid2, "Charlie")
            assertNotNull(err)
            assertTrue(err!!.contains("leader"))
        }

    @Test
    fun `membersInRoom returns only members in same room`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            val sid3 = SessionId(3L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")
            h.players.loginOrFail(sid3, "Charlie")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)
            h.group.invite(sid1, "Charlie")
            h.group.accept(sid3)

            // Move Charlie to different room
            h.players.moveTo(sid3, room2)

            val inRoom = h.group.membersInRoom(sid1, roomId)
            assertEquals(listOf(sid1, sid2), inRoom)
        }

    @Test
    fun `remapSession preserves group membership`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            val sid3 = SessionId(3L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)

            // Remap Alice's session (simulating takeover)
            h.group.remapSession(sid1, sid3)

            assertTrue(h.group.isGrouped(sid3))
            assertTrue(h.group.isLeader(sid3))
            assertFalse(h.group.isGrouped(sid1))
            assertEquals(listOf(sid3, sid2), h.group.groupMembers(sid3))
        }

    @Test
    fun `kick self fails`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)

            val err = h.group.kick(sid1, "Alice")
            assertNotNull(err)
            assertTrue(err!!.contains("yourself"))
        }

    @Test
    fun `kick dissolves two-member group`() =
        runTest {
            val h = setup()
            val sid1 = SessionId(1L)
            val sid2 = SessionId(2L)
            h.players.loginOrFail(sid1, "Alice")
            h.players.loginOrFail(sid2, "Bob")

            h.group.invite(sid1, "Bob")
            h.group.accept(sid2)

            val err = h.group.kick(sid1, "Bob")
            assertNull(err)

            assertFalse(h.group.isGrouped(sid1))
            assertFalse(h.group.isGrouped(sid2))
        }
}
