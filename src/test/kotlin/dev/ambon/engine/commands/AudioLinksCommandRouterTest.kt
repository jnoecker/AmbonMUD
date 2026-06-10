package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
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
class AudioLinksCommandRouterTest {
    @Test
    fun `audio links default off`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.players.loginOrFail(sid, "Alice")

            assertFalse(h.players.get(sid)!!.audioLinksEnabled)
        }

    @Test
    fun `audio on enables the flag`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(2)
            h.players.loginOrFail(sid, "Bob")

            h.router.handle(sid, Command.AudioLinksOn)
            val outs = h.outbound.drainAll()

            assertTrue(h.players.get(sid)!!.audioLinksEnabled)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("enabled") },
                "Expected confirmation, got=$outs",
            )
        }

    @Test
    fun `audio off disables the flag`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(3)
            h.players.loginOrFail(sid, "Carol")

            h.players.setAudioLinksEnabled(sid, true)
            assertTrue(h.players.get(sid)!!.audioLinksEnabled)

            h.router.handle(sid, Command.AudioLinksOff)
            val outs = h.outbound.drainAll()

            assertFalse(h.players.get(sid)!!.audioLinksEnabled)
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("disabled") },
                "Expected confirmation, got=$outs",
            )
        }

    @Test
    fun `bare audio toggles the flag`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(4)
            h.players.loginOrFail(sid, "Dave")

            h.router.handle(sid, Command.AudioLinksToggle)
            assertTrue(h.players.get(sid)!!.audioLinksEnabled)

            h.router.handle(sid, Command.AudioLinksToggle)
            assertFalse(h.players.get(sid)!!.audioLinksEnabled)
        }

    @Test
    fun `audio links toggle round-trips through PlayerRecord`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(5)
            h.players.loginOrFail(sid, "Eve")

            h.router.handle(sid, Command.AudioLinksOn)

            val state = h.players.get(sid)!!
            state.playerId = PlayerId(42L)
            val record = state.toPlayerRecord(lastSeenEpochMs = 123L)
            assertEquals(true, record.audioLinksEnabled)

            val restored = record.toPlayerState(SessionId(6))
            assertEquals(true, restored.audioLinksEnabled)
        }
}
