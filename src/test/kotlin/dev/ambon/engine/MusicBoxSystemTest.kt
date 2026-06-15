package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.MusicBox
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MusicBoxSystemTest {
    private val room = RoomId("academy:parlor")
    private val otherRoom = RoomId("academy:winter_walk")
    private val box =
        MusicBox(
            title = "Scuttlefish's Lullaby",
            url = "/audio/lullaby.mp3",
            durationSeconds = 60,
            lyrics = listOf("a", "b", "c"),
        )

    private fun system(nowMs: Long = 0, enabled: Boolean = true) =
        MutableClock(nowMs).let { it to MusicBoxSystem(it, enabled) }

    @Test
    fun `play records player-scoped state with the song's duration`() {
        val (_, mb) = system()
        val np = mb.play("Bob", box, room)

        assertTrue(np != null)
        np!!
        assertEquals("Scuttlefish's Lullaby", np.box.title)
        assertEquals(room, np.roomId)
        assertEquals(np, mb.nowPlaying("Bob"))
        assertEquals(60, np.remainingSeconds(0))
    }

    @Test
    fun `the song is personal — only the player who started it has it playing`() {
        val (_, mb) = system()
        mb.play("Bob", box, room)

        assertTrue(mb.nowPlaying("Bob") != null)
        assertNull(mb.nowPlaying("Carol"))
    }

    @Test
    fun `play replaces whatever the player already had going`() {
        val (clock, mb) = system()
        mb.play("Bob", box, room)
        clock.advance(20_000)

        val second = box.copy(title = "Second Tune", url = "/audio/second.mp3", durationSeconds = 30)
        val np = mb.play("Bob", second, otherRoom)

        assertEquals("Second Tune", np?.box?.title)
        assertEquals("Second Tune", mb.nowPlaying("Bob")?.box?.title)
        assertEquals(30, mb.secondsRemaining(mb.nowPlaying("Bob")!!))
    }

    @Test
    fun `the song expires lazily on read after its duration`() {
        val (clock, mb) = system()
        mb.play("Bob", box, room)

        clock.advance(60_000)
        assertNull(mb.nowPlaying("Bob"))
    }

    @Test
    fun `pollExpired clears ended songs once and returns them`() {
        val (clock, mb) = system()
        mb.play("Bob", box, room)
        mb.play("Carol", box.copy(durationSeconds = 120), room)

        clock.advance(60_000)
        val ended = mb.pollExpired()
        assertEquals(listOf("Bob"), ended.map { it.first })
        assertEquals("Scuttlefish's Lullaby", ended.single().second.box.title)
        // A second poll at the same instant returns nothing — it was cleared.
        assertTrue(mb.pollExpired().isEmpty())
        // Carol's longer song is still going.
        assertTrue(mb.nowPlaying("Carol") != null)
    }

    @Test
    fun `stop returns the playing song and clears it`() {
        val (clock, mb) = system()
        mb.play("Bob", box, room)
        clock.advance(10_000)

        val stopped = mb.stop("Bob")
        assertEquals("Scuttlefish's Lullaby", stopped?.box?.title)
        assertNull(mb.nowPlaying("Bob"))
        // pollExpired must not re-report a song that was explicitly stopped.
        clock.advance(60_000)
        assertTrue(mb.pollExpired().isEmpty())
    }

    @Test
    fun `stop is a no-op when nothing is playing`() {
        val (_, mb) = system()
        assertNull(mb.stop("Bob"))
    }

    @Test
    fun `clearPlayer forgets only that player's song`() {
        val (_, mb) = system()
        mb.play("Bob", box, room)
        mb.play("Carol", box, room)

        mb.clearPlayer("Bob")
        assertNull(mb.nowPlaying("Bob"))
        assertTrue(mb.nowPlaying("Carol") != null)
    }

    @Test
    fun `secondsRemaining counts down with the clock`() {
        val (clock, mb) = system()
        mb.play("Bob", box, room)
        clock.advance(45_000)

        assertEquals(15, mb.secondsRemaining(mb.nowPlaying("Bob")!!))
    }

    @Test
    fun `a disabled system never starts a song`() {
        val (_, mb) = system(enabled = false)
        assertNull(mb.play("Bob", box, room))
        assertNull(mb.nowPlaying("Bob"))
    }
}
