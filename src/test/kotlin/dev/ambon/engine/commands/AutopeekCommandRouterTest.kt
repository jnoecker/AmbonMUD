package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
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
class AutopeekCommandRouterTest {
    @Test
    fun `default autopeek is on and status reports ON`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.players.loginOrFail(sid, "Alice")

            assertTrue(h.players.get(sid)!!.autopeekEnabled)

            h.router.handle(sid, Command.AutopeekStatus)
            val outs = h.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("ON") },
                "Expected status ON message, got=$outs",
            )
        }

    @Test
    fun `look appends peek line naming the adjacent room when enabled`() =
        runTest {
            // Test world: hub --north--> outpost ("Test Outpost").
            val h = CommandRouterHarness.create()
            val sid = SessionId(2)
            h.players.loginOrFail(sid, "Bob")
            h.outbound.drainAll()

            h.router.handle(sid, Command.Look)
            val outs = h.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == "You see Test Outpost to the north." },
                "Expected peek line under room description, got=$outs",
            )
        }

    @Test
    fun `look omits peek line when disabled`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(3)
            h.players.loginOrFail(sid, "Carol")
            h.players.setAutopeekEnabled(sid, false)
            h.outbound.drainAll()

            h.router.handle(sid, Command.Look)
            val outs = h.outbound.drainAll()
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.text.startsWith("You see Test Outpost") },
                "Expected no peek line when autopeek is off, got=$outs",
            )
        }

    @Test
    fun `autopeek toggle takes effect immediately by refreshing the room view`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(4)
            h.players.loginOrFail(sid, "Dave")
            h.players.setAutopeekEnabled(sid, false)
            h.outbound.drainAll()

            h.router.handle(sid, Command.AutopeekOn)
            val onOuts = h.outbound.drainAll()
            assertTrue(h.players.get(sid)!!.autopeekEnabled)
            assertTrue(
                onOuts.any { it is OutboundEvent.SendInfo && it.text.contains("enabled") },
                "Expected confirmation, got=$onOuts",
            )
            assertTrue(
                onOuts.any { it is OutboundEvent.SendText && it.text == "You see Test Outpost to the north." },
                "Expected immediate room refresh with peek line, got=$onOuts",
            )

            h.router.handle(sid, Command.AutopeekOff)
            val offOuts = h.outbound.drainAll()
            assertFalse(h.players.get(sid)!!.autopeekEnabled)
            assertTrue(
                offOuts.any { it is OutboundEvent.SendInfo && it.text.contains("disabled") },
                "Expected confirmation, got=$offOuts",
            )
            assertFalse(
                offOuts.any { it is OutboundEvent.SendText && it.text.startsWith("You see Test Outpost") },
                "Expected refreshed room view without peek line, got=$offOuts",
            )
        }

    @Test
    fun `autopeek toggle round-trips through PlayerRecord`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(5)
            h.players.loginOrFail(sid, "Eve")

            h.router.handle(sid, Command.AutopeekOff)

            val state = h.players.get(sid)!!
            state.playerId = PlayerId(42L)
            val record = state.toPlayerRecord(lastSeenEpochMs = 123L)
            assertEquals(false, record.autopeekEnabled)

            val restored = record.toPlayerState(SessionId(6))
            assertEquals(false, restored.autopeekEnabled)
        }

    @Test
    fun `autopeek toggle emits Char Name GMCP updates for the web client`() =
        runTest {
            val outbound = LocalOutboundBus()
            val gmcpEmitter = GmcpEmitter(outbound = outbound, supportsPackage = { _, pkg -> pkg == "Char.Name" })
            val h = CommandRouterHarness.create(outbound = outbound, gmcpEmitter = gmcpEmitter)
            val sid = SessionId(6)
            h.players.loginOrFail(sid, "Finn")
            h.players.setAutopeekEnabled(sid, false)

            h.router.handle(sid, Command.AutopeekOn)
            val enableEvents = h.outbound.drainAll()
            val enablePacket = enableEvents
                .filterIsInstance<OutboundEvent.GmcpData>()
                .lastOrNull { it.gmcpPackage == "Char.Name" }
            assertTrue(
                enablePacket?.jsonData?.contains("\"autopeekEnabled\":true") == true,
                "Expected Char.Name GMCP update with autopeek enabled, got=$enableEvents",
            )

            h.router.handle(sid, Command.AutopeekOff)
            val disableEvents = h.outbound.drainAll()
            val disablePacket = disableEvents
                .filterIsInstance<OutboundEvent.GmcpData>()
                .lastOrNull { it.gmcpPackage == "Char.Name" }
            assertTrue(
                disablePacket?.jsonData?.contains("\"autopeekEnabled\":false") == true,
                "Expected Char.Name GMCP update with autopeek disabled, got=$disableEvents",
            )
        }
}
