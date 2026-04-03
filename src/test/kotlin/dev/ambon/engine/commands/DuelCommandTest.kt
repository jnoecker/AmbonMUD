package dev.ambon.engine.commands

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.DuelSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.MutableClock
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.errorMessages
import dev.ambon.test.infoMessages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuelCommandTest {
    private val roomA = RoomId("test:arena")
    private val roomB = RoomId("test:hall")

    private fun duelWorld(): World {
        val rooms = mapOf(
            roomA to Room(
                id = roomA,
                title = "Arena",
                description = "A fighting arena.",
                exits = mapOf(Direction.NORTH to roomB),
            ),
            roomB to Room(
                id = roomB,
                title = "Hall",
                description = "A hall.",
                exits = mapOf(Direction.SOUTH to roomA),
            ),
        )
        return World(rooms = rooms, startRoom = roomA)
    }

    private fun harness(clock: MutableClock = MutableClock(0L)): Pair<CommandRouterHarness, DuelSystem> {
        val world = duelWorld()
        val duelSystem = DuelSystem(clock)
        val players = buildTestPlayerRegistry(world.startRoom, clock = clock)
        val h = CommandRouterHarness.create(
            world = world,
            players = players,
            duelSystem = duelSystem,
            clock = clock,
        )
        return h to duelSystem
    }

    @Test
    fun `challenge sends notifications to both players and bystanders`() = runTest {
        val (h, _) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        val charlie = SessionId(3)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.loginPlayer(charlie, "Charlie")
        h.drain()

        h.router.handle(alice, Command.Duel("Bob"))

        val events = h.drain()
        val aliceInfos = events.infoMessages(alice)
        val bobInfos = events.infoMessages(bob)
        val charlieTexts = events.filterIsInstance<OutboundEvent.SendText>()
            .filter { it.sessionId == charlie }
            .map { it.text }

        assertTrue(aliceInfos.any { it.contains("challenge") && it.contains("Bob") }, "Alice sees challenge. got=$aliceInfos")
        assertTrue(bobInfos.any { it.contains("Alice") && it.contains("duel") }, "Bob sees challenge. got=$bobInfos")
        assertTrue(charlieTexts.any { it.contains("Alice") && it.contains("Bob") }, "Bystander sees broadcast. got=$charlieTexts")
    }

    @Test
    fun `accept starts duel and notifies both players`() = runTest {
        val (h, ds) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.drain()

        h.router.handle(alice, Command.Duel("Bob"))
        h.drain()

        h.router.handle(bob, Command.DuelAccept)

        val events = h.drain()
        val aliceInfos = events.infoMessages(alice)
        val bobInfos = events.infoMessages(bob)

        assertTrue(aliceInfos.any { it.contains("Fight") }, "Alice sees fight start. got=$aliceInfos")
        assertTrue(bobInfos.any { it.contains("Fight") || it.contains("accept") }, "Bob sees fight start. got=$bobInfos")
        assertTrue(ds.isInDuel(alice), "Alice should be in duel")
        assertTrue(ds.isInDuel(bob), "Bob should be in duel")
    }

    @Test
    fun `decline ends challenge and notifies both players`() = runTest {
        val (h, ds) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.drain()

        h.router.handle(alice, Command.Duel("Bob"))
        h.drain()

        h.router.handle(bob, Command.DuelDecline)

        val events = h.drain()
        val bobInfos = events.infoMessages(bob)
        val aliceInfos = events.infoMessages(alice)

        assertTrue(bobInfos.any { it.contains("decline") }, "Bob sees decline. got=$bobInfos")
        assertTrue(aliceInfos.any { it.contains("declined") }, "Alice is notified. got=$aliceInfos")
        assertNull(ds.getPendingChallenge(bob), "Challenge should be removed")
    }

    @Test
    fun `cannot duel yourself`() = runTest {
        val (h, _) = harness()
        val alice = SessionId(1)
        h.loginPlayer(alice, "Alice")
        h.drain()

        h.router.handle(alice, Command.Duel("Alice"))

        val errors = h.drain().errorMessages(alice)
        assertTrue(errors.any { it.contains("yourself") }, "got=$errors")
    }

    @Test
    fun `cannot challenge while already in duel`() = runTest {
        val (h, ds) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        val charlie = SessionId(3)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.loginPlayer(charlie, "Charlie")
        h.drain()

        // Start a duel
        h.router.handle(alice, Command.Duel("Bob"))
        h.drain()
        h.router.handle(bob, Command.DuelAccept)
        h.drain()
        assertTrue(ds.isInDuel(alice))

        // Try to challenge another player
        h.router.handle(alice, Command.Duel("Charlie"))

        val errors = h.drain().errorMessages(alice)
        assertTrue(errors.any { it.contains("already") }, "got=$errors")
    }

    @Test
    fun `accept with no pending challenge sends error`() = runTest {
        val (h, _) = harness()
        val alice = SessionId(1)
        h.loginPlayer(alice, "Alice")
        h.drain()

        h.router.handle(alice, Command.DuelAccept)

        val errors = h.drain().errorMessages(alice)
        assertTrue(errors.any { it.contains("No pending") }, "got=$errors")
    }

    @Test
    fun `decline with no pending challenge sends error`() = runTest {
        val (h, _) = harness()
        val alice = SessionId(1)
        h.loginPlayer(alice, "Alice")
        h.drain()

        h.router.handle(alice, Command.DuelDecline)

        val errors = h.drain().errorMessages(alice)
        assertTrue(errors.any { it.contains("No pending") }, "got=$errors")
    }

    @Test
    fun `challenge expires after timeout`() = runTest {
        val clock = MutableClock(0L)
        val (h, ds) = harness(clock)
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.drain()

        h.router.handle(alice, Command.Duel("Bob"))
        h.drain()

        assertNotNull(ds.getPendingChallenge(bob))

        // Advance past the 30-second timeout
        clock.advance(31_000L)

        // Try to accept — should fail because the challenge expired
        h.router.handle(bob, Command.DuelAccept)

        val errors = h.drain().errorMessages(bob)
        assertTrue(errors.any { it.contains("No pending") }, "got=$errors")
        assertFalse(ds.isInDuel(bob))
    }

    @Test
    fun `disconnect cleans up challenge and active duel`() = runTest {
        val (h, ds) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.drain()

        // Create a pending challenge
        h.router.handle(alice, Command.Duel("Bob"))
        h.drain()
        assertNotNull(ds.getPendingChallenge(bob))

        // Simulate disconnect
        val endedDuel = ds.onPlayerDisconnect(alice)
        assertNull(endedDuel, "No active duel to end")
        assertNull(ds.getPendingChallenge(bob), "Challenge should be cleaned up")
    }

    @Test
    fun `disconnect during active duel ends duel`() = runTest {
        val (h, ds) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.drain()

        h.router.handle(alice, Command.Duel("Bob"))
        h.drain()
        h.router.handle(bob, Command.DuelAccept)
        h.drain()
        assertTrue(ds.isInDuel(alice))
        assertTrue(ds.isInDuel(bob))

        // Disconnect alice
        val endedDuel = ds.onPlayerDisconnect(alice)
        assertNotNull(endedDuel, "Active duel should be returned")
        assertFalse(ds.isInDuel(alice), "Alice should no longer be in duel")
        assertFalse(ds.isInDuel(bob), "Bob should no longer be in duel")
    }

    @Test
    fun `cannot challenge player in different room`() = runTest {
        val (h, _) = harness()
        val alice = SessionId(1)
        val bob = SessionId(2)
        h.loginPlayer(alice, "Alice")
        h.loginPlayer(bob, "Bob")
        h.drain()

        // Move bob to a different room
        h.router.handle(bob, Command.Move(Direction.NORTH))
        h.drain()

        h.router.handle(alice, Command.Duel("Bob"))

        val errors = h.drain().errorMessages(alice)
        assertTrue(errors.any { it.contains("not here") }, "got=$errors")
    }

    @Test
    fun `duel unavailable sends error when system is null`() = runTest {
        val world = duelWorld()
        val h = CommandRouterHarness.create(world = world)
        val sid = SessionId(1)
        h.loginPlayer(sid, "Alice")
        h.drain()

        h.router.handle(sid, Command.Duel("Bob"))

        val errors = h.drain().errorMessages(sid)
        assertTrue(errors.any { it.contains("not available") }, "got=$errors")
    }
}
