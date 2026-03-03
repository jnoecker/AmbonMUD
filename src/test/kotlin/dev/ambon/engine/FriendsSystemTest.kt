package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FriendsSystemTest {
    private val roomId = RoomId("zone:room")

    private fun setup(maxFriends: Int = 50): TestHarness {
        val items = ItemRegistry()
        val playerRepo = InMemoryPlayerRepository()
        val players = buildTestPlayerRegistry(roomId, playerRepo, items)
        val outbound = LocalOutboundBus()
        val friends = FriendsSystem(
            players = players,
            outbound = outbound,
            maxFriends = maxFriends,
        )
        return TestHarness(players, playerRepo, outbound, friends, items)
    }

    private data class TestHarness(
        val players: PlayerRegistry,
        val playerRepo: InMemoryPlayerRepository,
        val outbound: LocalOutboundBus,
        val friends: FriendsSystem,
        val items: ItemRegistry,
    )

    @Test
    fun `add friend success`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        h.outbound.drainAll()

        val err = h.friends.addFriend(sid1, "Bob")
        assertNull(err)

        val ps = h.players.get(sid1)!!
        assertTrue(ps.friendsList.contains("bob"))

        val events = h.outbound.drainAll()
        assertTrue(events.any { it is OutboundEvent.SendInfo && it.text.contains("Bob") && it.text.contains("added") })
    }

    @Test
    fun `add friend with offline player`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        // Create Bob's account then log out
        h.players.loginOrFail(sid2, "Bob")
        h.players.disconnect(sid2)
        // Now Alice logs in
        h.players.loginOrFail(sid1, "Alice")
        h.outbound.drainAll()

        val err = h.friends.addFriend(sid1, "Bob")
        assertNull(err)

        val ps = h.players.get(sid1)!!
        assertTrue(ps.friendsList.contains("bob"))
    }

    @Test
    fun `cannot add self as friend`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.outbound.drainAll()

        val err = h.friends.addFriend(sid, "Alice")
        assertNotNull(err)
        assertTrue(err!!.contains("yourself"))
    }

    @Test
    fun `cannot add duplicate friend`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        h.outbound.drainAll()

        assertNull(h.friends.addFriend(sid1, "Bob"))
        val err = h.friends.addFriend(sid1, "Bob")
        assertNotNull(err)
        assertTrue(err!!.contains("already"))
    }

    @Test
    fun `cannot add nonexistent player`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.outbound.drainAll()

        val err = h.friends.addFriend(sid, "Nobody")
        assertNotNull(err)
        assertTrue(err!!.contains("No player"))
    }

    @Test
    fun `friends list full returns error`() = runTest {
        val h = setup(maxFriends = 2)
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        val sid3 = SessionId(3L)
        val sid4 = SessionId(4L)
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        h.players.loginOrFail(sid3, "Carol")
        h.players.loginOrFail(sid4, "Dave")
        h.outbound.drainAll()

        assertNull(h.friends.addFriend(sid1, "Bob"))
        assertNull(h.friends.addFriend(sid1, "Carol"))
        val err = h.friends.addFriend(sid1, "Dave")
        assertNotNull(err)
        assertTrue(err!!.contains("full"))
    }

    @Test
    fun `remove friend success`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        h.outbound.drainAll()

        assertNull(h.friends.addFriend(sid1, "Bob"))
        h.outbound.drainAll()

        val err = h.friends.removeFriend(sid1, "Bob")
        assertNull(err)

        val ps = h.players.get(sid1)!!
        assertTrue(ps.friendsList.isEmpty())

        val events = h.outbound.drainAll()
        assertTrue(events.any { it is OutboundEvent.SendInfo && it.text.contains("removed") })
    }

    @Test
    fun `remove friend not on list returns error`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.outbound.drainAll()

        val err = h.friends.removeFriend(sid, "Bob")
        assertNotNull(err)
        assertTrue(err!!.contains("not on your friends list"))
    }

    @Test
    fun `list friends when empty`() = runTest {
        val h = setup()
        val sid = SessionId(1L)
        h.players.loginOrFail(sid, "Alice")
        h.outbound.drainAll()

        val err = h.friends.listFriends(sid)
        assertNull(err)

        val events = h.outbound.drainAll()
        assertTrue(events.any { it is OutboundEvent.SendInfo && it.text.contains("empty") })
    }

    @Test
    fun `list friends shows online status and zone`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        h.outbound.drainAll()

        assertNull(h.friends.addFriend(sid1, "Bob"))
        h.outbound.drainAll()

        val err = h.friends.listFriends(sid1)
        assertNull(err)

        val events = h.outbound.drainAll()
        val infoEvent = events.filterIsInstance<OutboundEvent.SendInfo>().first()
        assertTrue(infoEvent.text.contains("Online"))
        assertTrue(infoEvent.text.contains("zone"))
    }

    @Test
    fun `list friends shows offline friend`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        assertNull(h.friends.addFriend(sid1, "Bob"))

        // Bob logs out
        h.players.disconnect(sid2)
        h.outbound.drainAll()

        val err = h.friends.listFriends(sid1)
        assertNull(err)

        val events = h.outbound.drainAll()
        val infoEvent = events.filterIsInstance<OutboundEvent.SendInfo>().first()
        assertTrue(infoEvent.text.contains("Offline"))
    }

    @Test
    fun `friend login notifies online friends`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)

        // Alice logs in and adds Bob as friend
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        assertNull(h.friends.addFriend(sid1, "Bob"))
        h.outbound.drainAll()

        // Simulate Bob logging out and back in
        h.players.disconnect(sid2)
        h.outbound.drainAll()

        val sid2b = SessionId(3L)
        h.players.loginOrFail(sid2b, "Bob")
        h.outbound.drainAll()

        h.friends.onPlayerLogin(sid2b)

        val events = h.outbound.drainAll()
        // Alice should get a notification
        val notification = events.filterIsInstance<OutboundEvent.SendInfo>()
            .filter { it.sessionId == sid1 }
        assertTrue(notification.any { it.text.contains("Bob") && it.text.contains("logged in") })
    }

    @Test
    fun `friend logout notifies online friends`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)

        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        assertNull(h.friends.addFriend(sid1, "Bob"))
        h.outbound.drainAll()

        h.friends.onPlayerLogout("Bob")

        val events = h.outbound.drainAll()
        val notification = events.filterIsInstance<OutboundEvent.SendInfo>()
            .filter { it.sessionId == sid1 }
        assertTrue(notification.any { it.text.contains("Bob") && it.text.contains("logged out") })
    }

    @Test
    fun `login notification is one-sided`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)

        // Alice adds Bob as friend, but Bob does NOT add Alice
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        assertNull(h.friends.addFriend(sid1, "Bob"))
        h.outbound.drainAll()

        // Simulate Alice logging out and back in
        h.players.disconnect(sid1)
        h.outbound.drainAll()

        val sid1b = SessionId(3L)
        h.players.loginOrFail(sid1b, "Alice")
        h.outbound.drainAll()

        h.friends.onPlayerLogin(sid1b)

        val events = h.outbound.drainAll()
        // Bob should NOT get a notification (he didn't add Alice)
        val bobNotifications = events.filterIsInstance<OutboundEvent.SendInfo>()
            .filter { it.sessionId == sid2 }
        assertTrue(bobNotifications.isEmpty())
    }

    @Test
    fun `add friend is case-insensitive`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        h.outbound.drainAll()

        assertNull(h.friends.addFriend(sid1, "BOB"))
        val ps = h.players.get(sid1)!!
        assertTrue(ps.friendsList.contains("bob"))

        // Adding again with different case should fail as duplicate
        val err = h.friends.addFriend(sid1, "bob")
        assertNotNull(err)
        assertTrue(err!!.contains("already"))
    }

    @Test
    fun `remove friend is case-insensitive`() = runTest {
        val h = setup()
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        h.players.loginOrFail(sid1, "Alice")
        h.players.loginOrFail(sid2, "Bob")
        h.outbound.drainAll()

        assertNull(h.friends.addFriend(sid1, "Bob"))
        assertNull(h.friends.removeFriend(sid1, "BOB"))

        val ps = h.players.get(sid1)!!
        assertTrue(ps.friendsList.isEmpty())
    }
}
