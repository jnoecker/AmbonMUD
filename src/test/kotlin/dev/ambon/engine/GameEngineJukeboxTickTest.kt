package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.GameEngineHarness
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Engine-loop coverage for the jukebox's text-only flavour: lyric lines broadcast
 * spread across the track and the end-of-song announcement, both driven by
 * [GameEngine]'s tick. The harness runs without a GMCP emitter, so this also
 * pins the telnet-only path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineJukeboxTickTest {
    @Test
    fun `lyrics broadcast across the track and the ending is announced`() =
        runTest {
            val clock = MutableClock(0)
            val world = WorldLoader.loadFromResource("world/ok_jukebox.yaml")
            val h = GameEngineHarness.start(scope = this, world = world, clock = clock)

            val sid = SessionId(1L)
            h.loginNewPlayer(sid, "Alice")
            runCurrent()
            advanceTimeBy(h.tickMillis * 20)
            runCurrent()
            val player = h.players.get(sid)
            require(player != null) { "Login did not complete; got=${h.drain()}" }
            player.gold = 50L
            h.drain()

            h.inbound.send(InboundEvent.LineReceived(sid, "jukebox play 1"))
            advanceTimeBy(h.tickMillis * 5)
            runCurrent()
            val playOuts = h.drain()
            assertTrue(
                playOuts.any { it is OutboundEvent.SendInfo && it.text.contains("Tavern Reel") },
                "Expected the play confirmation. got=$playOuts",
            )

            // Tavern Reel: 90s with 4 lyric lines -> one every 18s, starting at t=18s.
            clock.advance(18_000)
            advanceTimeBy(h.tickMillis * 2)
            runCurrent()
            val firstLyric = h.drain()
            assertTrue(
                firstLyric.any {
                    it is OutboundEvent.SendText && it.text == "♪ Oh the barkeep's cat ran out the door ♪"
                },
                "Expected the first lyric line. got=$firstLyric",
            )

            // Jump past the end: the remaining lines flush, then the ending is announced.
            clock.advance(72_000)
            advanceTimeBy(h.tickMillis * 2)
            runCurrent()
            val texts = h.drain().filterIsInstance<OutboundEvent.SendText>().map { it.text }
            val lastLyricIndex = texts.indexOf("♪ And the cat came back when the song was done ♪")
            val endIndex = texts.indexOfFirst { it.contains("winds down as \"Tavern Reel\"") }
            assertTrue(lastLyricIndex >= 0, "Expected the final lyric line. got=$texts")
            assertTrue(endIndex > lastLyricIndex, "Expected the ending after the final lyric. got=$texts")

            h.close()
        }
}
