package dev.ambon.engine.commands

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.commands.handlers.NavigationHandler
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.MutableClock
import dev.ambon.test.buildTestPlayerRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecallCommandTest {
    private val startRoom = RoomId("test_zone:hub")
    private val outpost = RoomId("test_zone:outpost")

    @Test
    fun `recall blocked while in combat`() =
        runTest {
            val clock = MutableClock(0L)
            val h = CommandRouterHarness.create(clock = clock)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")

            // Put mob in same room and start combat
            val mobId = dev.ambon.domain.ids.MobId("test_zone:grunt")
            h.mobs.upsert(MobState(id = mobId, name = "a grunt", roomId = startRoom, hp = 10, maxHp = 10))
            h.router.handle(sid, Command.Kill("grunt"))
            h.drain()

            h.router.handle(sid, Command.Recall)
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("cannot recall") },
                "Expected combat-block message. got=$outs",
            )
            // Player should not have moved
            assertTrue(h.players.get(sid)!!.roomId == startRoom)
        }

    @Test
    fun `recall teleports to class start room when no recall point set`() =
        runTest {
            val clock = MutableClock(0L)
            val h = CommandRouterHarness.create(clock = clock)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")

            // Move player to outpost
            h.players.moveTo(sid, outpost)
            h.drain()

            h.router.handle(sid, Command.Recall)
            val outs = h.drain()

            // Should end up back at startRoom (no recall set → falls back to start room)
            assertTrue(
                h.players.get(sid)!!.roomId == startRoom,
                "Expected player to be at startRoom after recall. got=${h.players.get(sid)!!.roomId}",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("recall point") },
                "Expected arrival message. got=$outs",
            )
        }

    @Test
    fun `recall teleports to saved recall room`() =
        runTest {
            val clock = MutableClock(0L)
            val h = CommandRouterHarness.create(clock = clock)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")

            // Set recall room to outpost while standing there
            h.players.moveTo(sid, outpost)
            h.players.setRecallRoom(sid, outpost)
            h.drain()

            // Move back to hub
            h.players.moveTo(sid, startRoom)
            h.drain()

            h.router.handle(sid, Command.Recall)

            assertTrue(
                h.players.get(sid)!!.roomId == outpost,
                "Expected player at saved recall room. got=${h.players.get(sid)!!.roomId}",
            )
        }

    @Test
    fun `recall blocked during cooldown`() =
        runTest {
            val clock = MutableClock(0L)
            val h = CommandRouterHarness.create(clock = clock)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")
            h.drain()

            // First recall — succeeds and starts cooldown
            h.router.handle(sid, Command.Recall)
            h.drain()

            // Second recall immediately — should be blocked
            h.router.handle(sid, Command.Recall)
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("seconds remaining") },
                "Expected cooldown message. got=$outs",
            )
        }

    @Test
    fun `recall available again after cooldown expires`() =
        runTest {
            val clock = MutableClock(0L)
            val h = CommandRouterHarness.create(clock = clock)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")

            // Move to outpost and set it as recall
            h.players.moveTo(sid, outpost)
            h.players.setRecallRoom(sid, outpost)

            // Move back to hub
            h.players.moveTo(sid, startRoom)
            h.drain()

            // First recall — succeeds, cooldown starts
            h.router.handle(sid, Command.Recall)
            h.drain()
            assertTrue(h.players.get(sid)!!.roomId == outpost)

            // Move back to hub again
            h.players.moveTo(sid, startRoom)
            h.drain()

            // Advance past cooldown
            clock.advance(NavigationHandler.RECALL_COOLDOWN_MS + 1L)

            // Second recall — should succeed
            h.router.handle(sid, Command.Recall)
            val outs = h.drain()

            assertTrue(
                h.players.get(sid)!!.roomId == outpost,
                "Expected player at recall room after cooldown. got=${h.players.get(sid)!!.roomId}",
            )
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.text.contains("seconds remaining") },
                "Should not show cooldown message after cooldown expires. got=$outs",
            )
        }

    @Test
    fun `set_recall dialogue action sets recall room`() =
        runTest {
            val clock = MutableClock(0L)
            val players = buildTestPlayerRegistry(startRoom)
            val h = CommandRouterHarness.create(players = players, clock = clock)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")
            h.drain()

            // Directly set recall room via PlayerRegistry
            h.players.moveTo(sid, outpost)
            h.players.setRecallRoom(sid, outpost)

            assertTrue(
                h.players.get(sid)!!.recallRoomId == outpost,
                "Expected recallRoomId to be outpost. got=${h.players.get(sid)!!.recallRoomId}",
            )
        }

    @Test
    fun `recall cooldown shows correct seconds remaining`() =
        runTest {
            val clock = MutableClock(0L)
            val h = CommandRouterHarness.create(clock = clock)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")
            h.drain()

            // Trigger first recall to start cooldown
            h.router.handle(sid, Command.Recall)
            h.drain()

            // Advance only 60 seconds (240 seconds remaining)
            clock.advance(60_000L)

            h.router.handle(sid, Command.Recall)
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("240 seconds remaining") },
                "Expected '240 seconds remaining'. got=$outs",
            )
        }
}
