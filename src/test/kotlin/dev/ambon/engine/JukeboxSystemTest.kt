package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.JukeboxSong
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JukeboxSystemTest {
    private val room = RoomId("academy:tavern")
    private val playlist =
        listOf(
            JukeboxSong("Tavern Reel", "/audio/reel.mp3", durationSeconds = 60, cost = 5),
            JukeboxSong("Aineroia's Ascension", "/audio/ascension.mp3", durationSeconds = 120, cost = 10),
        )

    private fun system(nowMs: Long = 0, enabled: Boolean = true) = MutableClock(nowMs).let { it to JukeboxSystem(it, enabled) }

    @Test
    fun `play deducts gold, records state, and overrides music`() {
        val (_, jb) = system()
        var gold = 50L
        val result = jb.play(room, playlist, songIndex = 0, buyerName = "Bob", currentGold = gold) { gold -= it }

        assertTrue(result is JukeboxPlayResult.Success)
        result as JukeboxPlayResult.Success
        assertEquals("Tavern Reel", result.nowPlaying.song.title)
        assertEquals("Bob", result.nowPlaying.buyerName)
        assertEquals(45L, gold)
        assertEquals("/audio/reel.mp3", jb.overrideMusic(room))
        assertEquals(60, jb.nowPlaying(room)?.remainingSeconds(0))
    }

    @Test
    fun `a playing room is locked until the track ends`() {
        val (clock, jb) = system()
        var gold = 100L
        jb.play(room, playlist, 0, "Bob", gold) { gold -= it }

        clock.advance(30_000)
        val busy = jb.play(room, playlist, 1, "Carol", gold) { gold -= it }
        assertTrue(busy is JukeboxPlayResult.Busy)
        busy as JukeboxPlayResult.Busy
        assertEquals("Tavern Reel", busy.current.song.title)
        assertEquals(30, busy.remainingSeconds)
        assertEquals(95L, gold) // Carol was not charged
    }

    @Test
    fun `track expires lazily on read after its duration`() {
        val (clock, jb) = system()
        var gold = 100L
        jb.play(room, playlist, 0, "Bob", gold) { gold -= it }

        clock.advance(60_000)
        assertNull(jb.nowPlaying(room))
        assertNull(jb.overrideMusic(room))
    }

    @Test
    fun `pollExpired returns only newly-ended tracks`() {
        val (clock, jb) = system()
        var gold = 100L
        jb.play(room, playlist, 0, "Bob", gold) { gold -= it }

        assertTrue(jb.pollExpired().isEmpty())
        clock.advance(60_000)
        val expired = jb.pollExpired()
        assertEquals(setOf(room), expired.keys)
        assertEquals("Tavern Reel", expired[room]?.song?.title)
        assertTrue(jb.pollExpired().isEmpty()) // already cleared
    }

    @Test
    fun `after a track ends a new song can play`() {
        val (clock, jb) = system()
        var gold = 100L
        jb.play(room, playlist, 0, "Bob", gold) { gold -= it }
        clock.advance(60_000)
        jb.pollExpired()

        val again = jb.play(room, playlist, 1, "Carol", gold) { gold -= it }
        assertTrue(again is JukeboxPlayResult.Success)
        assertEquals("/audio/ascension.mp3", jb.overrideMusic(room))
    }

    @Test
    fun `insufficient gold is rejected without charging`() {
        val (_, jb) = system()
        var gold = 3L
        val result = jb.play(room, playlist, 1, "Bob", gold) { gold -= it }
        assertTrue(result is JukeboxPlayResult.InsufficientGold)
        result as JukeboxPlayResult.InsufficientGold
        assertEquals(10L, result.need)
        assertEquals(3L, result.have)
        assertEquals(3L, gold)
        assertNull(jb.nowPlaying(room))
    }

    @Test
    fun `out-of-range song number is rejected`() {
        val (_, jb) = system()
        var gold = 100L
        val result = jb.play(room, playlist, 5, "Bob", gold) { gold -= it }
        assertTrue(result is JukeboxPlayResult.NoSuchSong)
        assertEquals(2, (result as JukeboxPlayResult.NoSuchSong).count)
    }

    @Test
    fun `empty playlist reports no jukebox`() {
        val (_, jb) = system()
        var gold = 100L
        val result = jb.play(room, emptyList(), 0, "Bob", gold) { gold -= it }
        assertTrue(result is JukeboxPlayResult.NoJukebox)
    }

    @Test
    fun `disabled system rejects all plays`() {
        val (_, jb) = system(enabled = false)
        var gold = 100L
        val result = jb.play(room, playlist, 0, "Bob", gold) { gold -= it }
        assertTrue(result is JukeboxPlayResult.Disabled)
    }

    // ---------- lyrics ----------

    private val ballad =
        listOf(
            JukeboxSong(
                "Cat Ballad",
                "/audio/ballad.mp3",
                durationSeconds = 100,
                cost = 5,
                lyrics = listOf("line one", "line two", "line three", "line four"),
            ),
        )

    @Test
    fun `lyrics come due spread evenly across the duration`() {
        // 4 lines over 100s -> one every 20s, starting at 20s and ending at 80s.
        val (clock, jb) = system()
        var gold = 100L
        jb.play(room, ballad, 0, "Bob", gold) { gold -= it }

        assertTrue(jb.pollDueLyrics().isEmpty()) // t=0: nothing due yet
        clock.advance(19_999)
        assertTrue(jb.pollDueLyrics().isEmpty())
        clock.advance(1)
        assertEquals(mapOf(room to listOf("line one")), jb.pollDueLyrics())
        assertTrue(jb.pollDueLyrics().isEmpty()) // no duplicates on re-poll
        clock.advance(40_000) // t=60s: lines two and three both due
        assertEquals(mapOf(room to listOf("line two", "line three")), jb.pollDueLyrics())
        clock.advance(20_000) // t=80s: final line, before the track ends at 100s
        assertEquals(mapOf(room to listOf("line four")), jb.pollDueLyrics())
        assertTrue(jb.pollDueLyrics().isEmpty())
        assertTrue(jb.pollExpired().isEmpty()) // still playing until t=100s
    }

    @Test
    fun `unsent lyrics flush when polled after the track ends`() {
        val (clock, jb) = system()
        var gold = 100L
        jb.play(room, ballad, 0, "Bob", gold) { gold -= it }

        clock.advance(100_000) // whole track elapsed without a lyric poll
        assertEquals(
            mapOf(room to listOf("line one", "line two", "line three", "line four")),
            jb.pollDueLyrics(),
        )
        assertEquals(setOf(room), jb.pollExpired().keys)
        assertTrue(jb.pollDueLyrics().isEmpty())
    }

    @Test
    fun `lyric progress resets for a new track in the same room`() {
        val (clock, jb) = system()
        var gold = 100L
        jb.play(room, ballad, 0, "Bob", gold) { gold -= it }
        clock.advance(100_000)
        jb.pollDueLyrics()
        jb.pollExpired()

        jb.play(room, ballad, 0, "Carol", gold) { gold -= it }
        assertTrue(jb.pollDueLyrics().isEmpty()) // fresh track: nothing due at t=0
        clock.advance(20_000)
        assertEquals(mapOf(room to listOf("line one")), jb.pollDueLyrics())
    }

    @Test
    fun `songs without lyrics never surface lyric lines`() {
        val (clock, jb) = system()
        var gold = 100L
        jb.play(room, playlist, 0, "Bob", gold) { gold -= it }
        clock.advance(30_000)
        assertTrue(jb.pollDueLyrics().isEmpty())
    }
}
