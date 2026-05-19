package dev.ambon.engine.commands

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestCommandTest {
    private val hub = RoomId("test_zone:hub")
    private val outpost = RoomId("test_zone:outpost")

    @Test
    fun `rest at inn sets recall point to current room`() =
        runTest {
            val h = CommandRouterHarness.create(clock = MutableClock(0L))
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")

            h.players.moveTo(sid, outpost)
            h.drain()

            h.router.handle(sid, Command.Rest)
            val outs = h.drain()

            assertEquals(
                outpost,
                h.players.get(sid)!!.recallRoomId,
                "Expected recall room set to inn after rest",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("recall point") },
                "Expected confirmation message. got=$outs",
            )
        }

    @Test
    fun `rest outside inn returns error and does not change recall`() =
        runTest {
            val h = CommandRouterHarness.create(clock = MutableClock(0L))
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")
            // Player starts in hub, which is not flagged as an inn.
            h.drain()

            val recallBefore = h.players.get(sid)!!.recallRoomId

            h.router.handle(sid, Command.Rest)
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("inn", ignoreCase = true) },
                "Expected 'inn' error message. got=$outs",
            )
            assertEquals(
                recallBefore,
                h.players.get(sid)!!.recallRoomId,
                "Recall should be unchanged after failed rest",
            )
            // Hub is not the inn, so recall must not be hub either.
            assertNotEquals(hub, h.players.get(sid)!!.recallRoomId)
        }

    @Test
    fun `rest blocked while in combat`() =
        runTest {
            val h = CommandRouterHarness.create(clock = MutableClock(0L))
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")
            h.players.moveTo(sid, outpost)

            val mobId = dev.ambon.domain.ids.MobId("test_zone:grunt")
            h.mobs.upsert(
                dev.ambon.domain.mob.MobState(
                    id = mobId,
                    name = "a grunt",
                    roomId = outpost,
                    hp = 10,
                    maxHp = 10,
                ),
            )
            h.router.handle(sid, Command.Kill("grunt"))
            h.drain()

            h.router.handle(sid, Command.Rest)
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("cannot recall") },
                "Expected combat-blocked message. got=$outs",
            )
        }
}
