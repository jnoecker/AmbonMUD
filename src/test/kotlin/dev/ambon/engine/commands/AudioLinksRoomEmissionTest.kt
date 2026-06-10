package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioLinksRoomEmissionTest {
    private val musicWorld by lazy { WorldLoader.loadFromResource("world/ok_music.yaml") }

    private fun infoLines(events: List<OutboundEvent>): List<String> =
        events.filterIsInstance<OutboundEvent.SendInfo>().map { it.text }

    @Test
    fun `look in a music room prints inline music and ambient links when enabled`() =
        runTest {
            val h = CommandRouterHarness.create(world = musicWorld)
            val sid = SessionId(1)
            h.players.loginOrFail(sid, "Alice")
            h.players.setAudioLinksEnabled(sid, true)
            h.outbound.drainAll()

            h.router.handle(sid, Command.Look)
            val lines = infoLines(h.outbound.drainAll())

            // WorldLoader resolves room.music/ambient against the audio base (default "/audio/"),
            // so the inline link is the same fully-resolved URL the web client receives.
            assertTrue(
                lines.contains("[music] /audio/music/hall.mp3"),
                "Expected inline music link, got=$lines",
            )
            assertTrue(
                lines.contains("[ambient] /audio/ambient/crowd.mp3"),
                "Expected inline ambient link, got=$lines",
            )
        }

    @Test
    fun `no inline audio links when disabled`() =
        runTest {
            val h = CommandRouterHarness.create(world = musicWorld)
            val sid = SessionId(2)
            h.players.loginOrFail(sid, "Bob")
            h.outbound.drainAll()

            h.router.handle(sid, Command.Look)
            val lines = infoLines(h.outbound.drainAll())

            assertFalse(
                lines.any { it.startsWith("[music]") || it.startsWith("[ambient]") },
                "Expected no inline audio links when disabled, got=$lines",
            )
        }

    @Test
    fun `unchanged track is not reprinted on a second look`() =
        runTest {
            val h = CommandRouterHarness.create(world = musicWorld)
            val sid = SessionId(3)
            h.players.loginOrFail(sid, "Carol")
            h.players.setAudioLinksEnabled(sid, true)
            h.outbound.drainAll()

            h.router.handle(sid, Command.Look)
            h.outbound.drainAll()

            // Second look in the same room — track hasn't changed, so nothing reprinted.
            h.router.handle(sid, Command.Look)
            val lines = infoLines(h.outbound.drainAll())

            assertFalse(
                lines.any { it.startsWith("[music]") || it.startsWith("[ambient]") },
                "Expected no reprint of unchanged track, got=$lines",
            )
        }

    @Test
    fun `moving to a room with a different track reprints music`() =
        runTest {
            val h = CommandRouterHarness.create(world = musicWorld)
            val sid = SessionId(4)
            h.players.loginOrFail(sid, "Dave")
            h.players.setAudioLinksEnabled(sid, true)
            h.outbound.drainAll()

            h.router.handle(sid, Command.Look) // hub: hall.mp3
            h.outbound.drainAll()

            // East to "far" which plays a different track.
            h.router.handle(sid, Command.Move(Direction.EAST))
            val lines = infoLines(h.outbound.drainAll())

            assertTrue(
                lines.contains("[music] /audio/music/grove.mp3"),
                "Expected new music link after moving to a different track, got=$lines",
            )
        }
}
