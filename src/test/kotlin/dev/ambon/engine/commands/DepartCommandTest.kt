package dev.ambon.engine.commands

import dev.ambon.config.DeathConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DepartCommandTest {
    private val hub = RoomId("test_zone:hub")
    private val outpost = RoomId("test_zone:outpost")

    @Test
    fun `depart from sanctum returns to last death zone start`() =
        runTest {
            val h = CommandRouterHarness.create(
                clock = MutableClock(0L),
                deathConfig = DeathConfig(sanctumRoom = outpost.value),
            )
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")

            // Simulate having died in test_zone and been respawned in the sanctum (outpost).
            h.players.moveTo(sid, outpost)
            val player = h.players.get(sid)!!
            player.lastDeathZone = "test_zone"
            h.drain()

            h.router.handle(sid, Command.Depart)
            h.drain()

            assertEquals(hub, h.players.get(sid)!!.roomId, "Expected to land at test_zone start (hub)")
            assertNull(h.players.get(sid)!!.lastDeathZone, "lastDeathZone should be cleared after successful depart")
        }

    @Test
    fun `depart outside sanctum errors`() =
        runTest {
            val h = CommandRouterHarness.create(
                clock = MutableClock(0L),
                deathConfig = DeathConfig(sanctumRoom = outpost.value),
            )
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")
            // Player is in hub, not in the sanctum (outpost).
            h.players.get(sid)!!.lastDeathZone = "test_zone"
            h.drain()

            h.router.handle(sid, Command.Depart)
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("sanctum") },
                "Expected sanctum-required error. got=$outs",
            )
            assertEquals(hub, h.players.get(sid)!!.roomId, "Player should not move")
        }

    @Test
    fun `depart with no recorded death errors`() =
        runTest {
            val h = CommandRouterHarness.create(
                clock = MutableClock(0L),
                deathConfig = DeathConfig(sanctumRoom = outpost.value),
            )
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")
            // Player is in sanctum but has no recorded death zone (fresh character).
            h.players.moveTo(sid, outpost)
            h.drain()

            h.router.handle(sid, Command.Depart)
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("nowhere to return") },
                "Expected 'nowhere to return' error. got=$outs",
            )
        }
}
