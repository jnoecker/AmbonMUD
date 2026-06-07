package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.toPlayerRecord
import dev.ambon.engine.toPlayerState
import dev.ambon.persistence.PlayerId
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ZoneCinematicCommandRouterTest {
    private fun cinematicPackets(events: List<OutboundEvent>): List<OutboundEvent.GmcpData> =
        events.filterIsInstance<OutboundEvent.GmcpData>().filter { it.gmcpPackage == "Zone.Cinematic" }

    private fun createHarness(): CommandRouterHarness {
        val outbound = LocalOutboundBus()
        val gmcpEmitter = GmcpEmitter(outbound = outbound, supportsPackage = { _, _ -> true })
        return CommandRouterHarness.create(
            world = WorldLoader.loadFromResource("world/ok_zone_video.yaml"),
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
        )
    }

    @Test
    fun `first zone entry autoplays the cinematic and marks it seen`() =
        runTest {
            val h = createHarness()
            val sid = SessionId(1)
            h.players.loginOrFail(sid, "Alice")
            h.outbound.drainAll()

            h.router.handle(sid, Command.Look)
            val packets = cinematicPackets(h.outbound.drainAll())
            assertEquals(1, packets.size, "Expected one Zone.Cinematic packet")
            assertTrue(
                packets[0].jsonData.contains("\"url\":\"/videos/intro_flyover.mp4\"") &&
                    packets[0].jsonData.contains("\"autoplay\":true"),
                "Expected autoplay cinematic, got=${packets[0].jsonData}",
            )
            assertTrue("ok_zone_video" in h.players.get(sid)!!.seenZoneCinematics)
        }

    @Test
    fun `subsequent looks in the same zone do not resend the cinematic`() =
        runTest {
            val h = createHarness()
            val sid = SessionId(2)
            h.players.loginOrFail(sid, "Bob")
            h.router.handle(sid, Command.Look)
            h.outbound.drainAll()

            h.router.handle(sid, Command.Look)
            assertTrue(
                cinematicPackets(h.outbound.drainAll()).isEmpty(),
                "Zone unchanged — no Zone.Cinematic resend expected",
            )
        }

    @Test
    fun `players who already watched the cinematic get it without autoplay`() =
        runTest {
            val h = createHarness()
            val sid = SessionId(3)
            h.players.loginOrFail(sid, "Carol")
            h.players.get(sid)!!.seenZoneCinematics.add("ok_zone_video")
            h.outbound.drainAll()

            h.router.handle(sid, Command.Look)
            val packets = cinematicPackets(h.outbound.drainAll())
            assertEquals(1, packets.size, "Replay URL should still be sent on zone entry")
            assertTrue(
                packets[0].jsonData.contains("\"autoplay\":false"),
                "Expected autoplay=false for already-seen cinematic, got=${packets[0].jsonData}",
            )
        }

    @Test
    fun `zones without a cinematic emit nothing`() =
        runTest {
            val h = CommandRouterHarness.create() // default test world has no zone video
            val sid = SessionId(4)
            h.players.loginOrFail(sid, "Dave")
            h.outbound.drainAll()

            h.router.handle(sid, Command.Look)
            assertTrue(cinematicPackets(h.outbound.drainAll()).isEmpty())
        }

    @Test
    fun `seenZoneCinematics round-trips through PlayerRecord`() =
        runTest {
            val h = createHarness()
            val sid = SessionId(5)
            h.players.loginOrFail(sid, "Eve")
            h.router.handle(sid, Command.Look)

            val state = h.players.get(sid)!!
            state.playerId = PlayerId(7L)
            val record = state.toPlayerRecord(lastSeenEpochMs = 123L)
            assertEquals(setOf("ok_zone_video"), record.seenZoneCinematics)

            val restored = record.toPlayerState(SessionId(6))
            assertTrue("ok_zone_video" in restored.seenZoneCinematics)
            assertFalse("other_zone" in restored.seenZoneCinematics)
        }
}
