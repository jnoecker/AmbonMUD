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

    @Test
    fun `a queued track takes over when the current one ends`() =
        runTest {
            val clock = MutableClock(0)
            val world = WorldLoader.loadFromResource("world/ok_jukebox.yaml")
            val h = GameEngineHarness.start(scope = this, world = world, clock = clock)

            val aliceSid = SessionId(1L)
            h.loginNewPlayer(aliceSid, "Alice")
            val beaSid = SessionId(2L)
            h.loginNewPlayer(beaSid, "Bea")
            runCurrent()
            advanceTimeBy(h.tickMillis * 20)
            runCurrent()
            val alice = h.players.get(aliceSid)
            val bea = h.players.get(beaSid)
            require(alice != null && bea != null) { "Logins did not complete; got=${h.drain()}" }
            alice.gold = 50L
            bea.gold = 50L
            h.players.setAudioLinksEnabled(aliceSid, true)
            h.drain()

            h.inbound.send(InboundEvent.LineReceived(aliceSid, "jukebox play 1"))
            advanceTimeBy(h.tickMillis * 5)
            runCurrent()
            h.drain()
            h.inbound.send(InboundEvent.LineReceived(beaSid, "jukebox queue 2"))
            advanceTimeBy(h.tickMillis * 5)
            runCurrent()
            val queueOuts = h.drain()
            assertTrue(
                queueOuts.any { it is OutboundEvent.SendInfo && it.text.contains("will play next") },
                "Expected the queue confirmation. got=$queueOuts",
            )

            // Jump past the end of the 90s reel: the queued track takes over seamlessly.
            clock.advance(91_000)
            advanceTimeBy(h.tickMillis * 2)
            runCurrent()
            val outs = h.drain()
            val texts = outs.filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(
                texts.any { it.contains("winds down as \"Tavern Reel\"") },
                "Expected the first track's ending announcement. got=$texts",
            )
            assertTrue(
                texts.any { it.contains("whirs back to life with \"Aineroia's Ascension\"") && it.contains("queued by Bea") },
                "Expected the takeover announcement. got=$texts",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == aliceSid &&
                        it.text == "[music] /audio/jukebox/ascension.mp3"
                },
                "Expected the inline link to follow the promoted track. got=$outs",
            )

            // And when the promoted track ends with no successor, the room reverts.
            clock.advance(181_000)
            advanceTimeBy(h.tickMillis * 2)
            runCurrent()
            val endOuts = h.drain()
            assertTrue(
                endOuts.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == aliceSid &&
                        it.text == "[music] /audio/tavern/ambient_loop.mp3"
                },
                "Expected the default music after the queue drained. got=$endOuts",
            )

            h.close()
        }

    @Test
    fun `inline audio links follow the jukebox track and revert when it ends`() =
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
            h.players.setAudioLinksEnabled(sid, true)
            h.drain()

            h.inbound.send(InboundEvent.LineReceived(sid, "jukebox play 1"))
            advanceTimeBy(h.tickMillis * 5)
            runCurrent()
            val playOuts = h.drain()
            assertTrue(
                playOuts.any { it is OutboundEvent.SendInfo && it.text == "[music] /audio/jukebox/tavern_reel.mp3" },
                "Expected the jukebox track's inline link on play. got=$playOuts",
            )

            // Jump past the end of the 90s track: the room reverts to its default music.
            clock.advance(91_000)
            advanceTimeBy(h.tickMillis * 2)
            runCurrent()
            val endOuts = h.drain()
            assertTrue(
                endOuts.any { it is OutboundEvent.SendInfo && it.text == "[music] /audio/tavern/ambient_loop.mp3" },
                "Expected the room's default music link after the track ended. got=$endOuts",
            )

            h.close()
        }
}
