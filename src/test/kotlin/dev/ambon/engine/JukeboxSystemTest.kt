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
    fun `pollExpired returns only newly-ended rooms`() {
        val (clock, jb) = system()
        var gold = 100L
        jb.play(room, playlist, 0, "Bob", gold) { gold -= it }

        assertEquals(emptyList<RoomId>(), jb.pollExpired())
        clock.advance(60_000)
        assertEquals(listOf(room), jb.pollExpired())
        assertEquals(emptyList<RoomId>(), jb.pollExpired()) // already cleared
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
}
