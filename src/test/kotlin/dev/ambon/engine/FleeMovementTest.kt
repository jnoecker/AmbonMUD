package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.engine.commands.Command
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.GameEngineHarness
import dev.ambon.test.TestWorlds
import dev.ambon.test.createTestPlayer
import dev.ambon.test.drainAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FleeMovementTest {
    @Test
    fun `directional move broadcasts use 'exits to' and 'arrives from' phrasing`() =
        runTest {
            val h = CommandRouterHarness.create()
            val alice = SessionId(1)
            val bob = SessionId(2)
            val charlie = SessionId(3)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.loginPlayer(charlie, "Charlie")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val northDir = startRoom.exits.keys.first { it == Direction.NORTH }

            // Park Charlie in the north room so he sees the arrival.
            h.router.handle(charlie, Command.Move(northDir))
            h.drain()

            h.router.handle(alice, Command.Move(northDir))
            val outs = h.drain()

            // Bob (in starting room) sees the directional depart.
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText &&
                        it.sessionId == bob &&
                        it.text == "Alice exits to the north."
                },
                "Expected directional depart for Bob. got=$outs",
            )
            // Charlie (in destination room) sees the directional arrive.
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText &&
                        it.sessionId == charlie &&
                        it.text == "Alice arrives from the south."
                },
                "Expected directional arrive for Charlie. got=$outs",
            )
        }

    @Test
    fun `move populates lastEnterDirection so flee can retrace`() =
        runTest {
            val h = CommandRouterHarness.create()
            val alice = SessionId(1)
            h.loginPlayer(alice, "Alice")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val northDir = startRoom.exits.keys.first { it == Direction.NORTH }

            h.router.handle(alice, Command.Move(northDir))
            h.drain()

            assertEquals(
                northDir,
                h.players.get(alice)?.lastEnterDirection,
                "Expected lastEnterDirection set to the move direction",
            )
        }

    @Test
    fun `flee from PvE relocates the player back the way they came`() =
        runTest {
            // ok_small: a (start) --N--> b (rat). Walking north sets lastEnterDirection=NORTH;
            // flee should pick SOUTH (opposite) and put the player back in 'a'.
            val clock = java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)
            val repo = InMemoryPlayerRepository()
            repo.createTestPlayer("Alice", TestWorlds.okSmall.startRoom, password = "pw")
            val h = GameEngineHarness.start(scope = this, world = TestWorlds.okSmall, clock = clock, repo = repo)

            val sid = SessionId(1L)
            runCurrent()
            h.inbound.send(InboundEvent.Connected(sid))
            h.inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            h.inbound.send(InboundEvent.LineReceived(sid, "pw"))
            h.inbound.send(InboundEvent.LineReceived(sid, "n"))
            h.inbound.send(InboundEvent.LineReceived(sid, "kill rat"))
            advanceTimeBy(h.tickMillis)
            runCurrent()
            h.outbound.drainAll()

            val roomA = RoomId("ok_small:a")
            val roomB = RoomId("ok_small:b")
            assertEquals(roomB, h.players.get(sid)?.roomId, "Should be in rat room before flee")

            h.inbound.send(InboundEvent.LineReceived(sid, "flee"))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            assertEquals(roomA, h.players.get(sid)?.roomId, "Flee should retrace south to room a")
            assertEquals(
                Direction.SOUTH,
                h.players.get(sid)?.lastEnterDirection,
                "lastEnterDirection should track the flee direction",
            )

            h.close()
        }

    @Test
    fun `flee broadcasts directional 'flees to' message to observers in old room`() =
        runTest {
            // ok_small: only one player in this scenario for simplicity. Add a witness in
            // room b by reusing the harness: log Bob into b directly via repo so he sees
            // Alice's flee broadcast leaving b.
            val clock = java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)
            val repo = InMemoryPlayerRepository()
            repo.createTestPlayer("Alice", TestWorlds.okSmall.startRoom, password = "pw")
            repo.createTestPlayer("Bob", RoomId("ok_small:b"), password = "pw")
            val h = GameEngineHarness.start(scope = this, world = TestWorlds.okSmall, clock = clock, repo = repo)

            val aliceSid = SessionId(1L)
            val bobSid = SessionId(2L)
            runCurrent()

            // Bob logs in first and stays in room b.
            h.inbound.send(InboundEvent.Connected(bobSid))
            h.inbound.send(InboundEvent.LineReceived(bobSid, "Bob"))
            h.inbound.send(InboundEvent.LineReceived(bobSid, "pw"))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            // Alice walks north into b and starts combat.
            h.inbound.send(InboundEvent.Connected(aliceSid))
            h.inbound.send(InboundEvent.LineReceived(aliceSid, "Alice"))
            h.inbound.send(InboundEvent.LineReceived(aliceSid, "pw"))
            h.inbound.send(InboundEvent.LineReceived(aliceSid, "n"))
            h.inbound.send(InboundEvent.LineReceived(aliceSid, "kill rat"))
            advanceTimeBy(h.tickMillis)
            runCurrent()
            h.outbound.drainAll()

            // Flee — observed by Bob who is still in b.
            h.inbound.send(InboundEvent.LineReceived(aliceSid, "flee"))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            val outs = h.outbound.drainAll()
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText &&
                        it.sessionId == bobSid &&
                        it.text == "Alice flees to the south."
                },
                "Expected directional flee broadcast in old room. got=$outs",
            )

            h.close()
        }

    @Test
    fun `flee respects achievement gates and never picks a gated exit`() =
        runTest {
            // ok_flee_gate: hall has open south (foyer) and achievement-gated north (sanctum).
            // Alice starts in sanctum and walks south into hall, so lastEnterDirection = SOUTH
            // and opposite = NORTH — which is the gated exit. With the gate honored, flee
            // must reject NORTH and fall back to SOUTH (foyer); without the fix, the gated
            // NORTH would be picked deterministically as the preferred direction.
            val clock = java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)
            val repo = InMemoryPlayerRepository()
            repo.createTestPlayer("Alice", RoomId("ok_flee_gate:sanctum"), password = "pw")
            val h = GameEngineHarness.start(scope = this, world = TestWorlds.okFleeGate, clock = clock, repo = repo)

            val sid = SessionId(1L)
            runCurrent()
            h.inbound.send(InboundEvent.Connected(sid))
            h.inbound.send(InboundEvent.LineReceived(sid, "Alice"))
            h.inbound.send(InboundEvent.LineReceived(sid, "pw"))
            h.inbound.send(InboundEvent.LineReceived(sid, "s"))
            h.inbound.send(InboundEvent.LineReceived(sid, "kill rat"))
            advanceTimeBy(h.tickMillis)
            runCurrent()
            h.outbound.drainAll()

            val hall = RoomId("ok_flee_gate:hall")
            val foyer = RoomId("ok_flee_gate:foyer")
            val sanctum = RoomId("ok_flee_gate:sanctum")
            assertEquals(hall, h.players.get(sid)?.roomId, "Should be in hall before flee")
            assertEquals(
                Direction.SOUTH,
                h.players.get(sid)?.lastEnterDirection,
                "Setup precondition: walked south into hall",
            )

            h.inbound.send(InboundEvent.LineReceived(sid, "flee"))
            advanceTimeBy(h.tickMillis)
            runCurrent()

            val landedIn = h.players.get(sid)?.roomId
            assertNotEquals(sanctum, landedIn, "Flee must not bypass achievement gate to sanctum")
            assertEquals(foyer, landedIn, "Flee should fall back to the open south exit")

            h.close()
        }

    @Test
    fun `recall clears lastEnterDirection so subsequent flee picks random exit`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val northDir = startRoom.exits.keys.firstOrNull { it == Direction.NORTH } ?: return@runTest

            h.router.handle(sid, Command.Move(northDir))
            h.drain()
            assertNotEquals(null, h.players.get(sid)?.lastEnterDirection)

            h.router.handle(sid, Command.Recall)
            h.drain()

            assertNull(
                h.players.get(sid)?.lastEnterDirection,
                "Recall should clear lastEnterDirection",
            )
        }
}
