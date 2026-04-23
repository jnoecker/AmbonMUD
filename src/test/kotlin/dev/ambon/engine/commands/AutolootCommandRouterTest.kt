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
class AutolootCommandRouterTest {
    @Test
    fun `default autoloot is off and status reports OFF`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.players.loginOrFail(sid, "Alice")

            assertFalse(h.players.get(sid)!!.autolootEnabled)

            h.router.handle(sid, Command.AutolootStatus)
            val outs = h.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("OFF") },
                "Expected status OFF message, got=$outs",
            )
        }

    @Test
    fun `autoloot on enables the flag`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(2)
            h.players.loginOrFail(sid, "Bob")

            h.router.handle(sid, Command.AutolootOn)
            val outs = h.outbound.drainAll()

            assertTrue(h.players.get(sid)!!.autolootEnabled)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("enabled") },
                "Expected confirmation, got=$outs",
            )
        }

    @Test
    fun `autoloot off disables the flag`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(3)
            h.players.loginOrFail(sid, "Carol")

            h.players.setAutolootEnabled(sid, true)
            assertTrue(h.players.get(sid)!!.autolootEnabled)

            h.router.handle(sid, Command.AutolootOff)
            val outs = h.outbound.drainAll()

            assertFalse(h.players.get(sid)!!.autolootEnabled)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("disabled") },
                "Expected confirmation, got=$outs",
            )
        }

    @Test
    fun `autoloot status reflects current toggle after on and off`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(4)
            h.players.loginOrFail(sid, "Dave")

            h.router.handle(sid, Command.AutolootOn)
            h.outbound.drainAll()

            h.router.handle(sid, Command.AutolootStatus)
            val onOuts = h.outbound.drainAll()
            assertTrue(
                onOuts.any { it is OutboundEvent.SendInfo && it.text.contains("ON") },
                "Expected status ON after enabling, got=$onOuts",
            )

            h.router.handle(sid, Command.AutolootOff)
            h.outbound.drainAll()
            h.router.handle(sid, Command.AutolootStatus)
            val offOuts = h.outbound.drainAll()
            assertTrue(
                offOuts.any { it is OutboundEvent.SendInfo && it.text.contains("OFF") },
                "Expected status OFF after disabling, got=$offOuts",
            )
        }

    @Test
    fun `autoloot toggle round-trips through PlayerRecord`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(5)
            h.players.loginOrFail(sid, "Eve")

            h.router.handle(sid, Command.AutolootOn)

            val state = h.players.get(sid)!!
            state.playerId = PlayerId(42L)
            val record = state.toPlayerRecord(lastSeenEpochMs = 123L)
            assertEquals(true, record.autolootEnabled)

            val restored = record.toPlayerState(SessionId(6))
            assertEquals(true, restored.autolootEnabled)
        }

    @Test
    fun `autoloot toggle emits Char Name GMCP updates for the web client`() =
        runTest {
            val outbound = LocalOutboundBus()
            val gmcpEmitter = GmcpEmitter(outbound = outbound, supportsPackage = { _, pkg -> pkg == "Char.Name" })
            val h = CommandRouterHarness.create(outbound = outbound, gmcpEmitter = gmcpEmitter)
            val sid = SessionId(6)
            h.players.loginOrFail(sid, "Finn")

            h.router.handle(sid, Command.AutolootOn)
            val enableEvents = h.outbound.drainAll()
            val enablePacket = enableEvents
                .filterIsInstance<OutboundEvent.GmcpData>()
                .lastOrNull { it.gmcpPackage == "Char.Name" }
            assertTrue(
                enablePacket?.jsonData?.contains("\"autolootEnabled\":true") == true,
                "Expected Char.Name GMCP update with autoloot enabled, got=$enableEvents",
            )

            h.router.handle(sid, Command.AutolootOff)
            val disableEvents = h.outbound.drainAll()
            val disablePacket = disableEvents
                .filterIsInstance<OutboundEvent.GmcpData>()
                .lastOrNull { it.gmcpPackage == "Char.Name" }
            assertTrue(
                disablePacket?.jsonData?.contains("\"autolootEnabled\":false") == true,
                "Expected Char.Name GMCP update with autoloot disabled, got=$disableEvents",
            )
        }
}
