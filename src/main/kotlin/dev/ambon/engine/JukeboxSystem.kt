package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.JukeboxSong
import java.time.Clock

/** What a room's jukebox is currently playing, and until when. */
data class JukeboxNowPlaying(
    val song: JukeboxSong,
    /** 0-based index into the room's playlist. */
    val songIndex: Int,
    /** Name of the player who paid for this track. */
    val buyerName: String,
    /** Epoch millis at which the track started playing. */
    val startedAtMs: Long,
    /** Epoch millis at which the track stops and the room reverts to default music. */
    val endsAtMs: Long,
) {
    fun remainingSeconds(nowMs: Long): Int = (((endsAtMs - nowMs) + 999) / 1000).coerceAtLeast(0).toInt()

    /**
     * How many of the song's lyric lines are due by [nowMs]. The N lines are
     * spread evenly across the track: line i (0-based) comes due at
     * `startedAtMs + (i + 1) * duration / (N + 1)`, so the first line lands a
     * beat after the start announcement and the last lands before the track ends.
     */
    fun dueLyricCount(nowMs: Long): Int {
        val lyrics = song.lyrics
        if (lyrics.isEmpty()) return 0
        if (nowMs >= endsAtMs) return lyrics.size
        val gapMs = (endsAtMs - startedAtMs) / (lyrics.size + 1)
        if (gapMs <= 0) return lyrics.size
        return ((nowMs - startedAtMs) / gapMs).toInt().coerceIn(0, lyrics.size)
    }
}

/** Outcome of a `jukebox play` attempt. */
sealed interface JukeboxPlayResult {
    /** The track started; [nowPlaying] is the room's new state. */
    data class Success(
        val nowPlaying: JukeboxNowPlaying,
    ) : JukeboxPlayResult

    /** The jukebox feature is disabled server-wide. */
    data object Disabled : JukeboxPlayResult

    /** This room has no jukebox playlist. */
    data object NoJukebox : JukeboxPlayResult

    /** The requested song number was out of range; [count] songs are available. */
    data class NoSuchSong(
        val count: Int,
    ) : JukeboxPlayResult

    /** A track is already playing and locks the jukebox until it ends. */
    data class Busy(
        val current: JukeboxNowPlaying,
        val remainingSeconds: Int,
    ) : JukeboxPlayResult

    /** The player cannot afford the track. */
    data class InsufficientGold(
        val need: Long,
        val have: Long,
    ) : JukeboxPlayResult
}

/**
 * Tracks each room's currently-playing jukebox track. State is transient (in
 * memory, reset on restart) — a paid song locks the room for its authored
 * [JukeboxSong.durationSeconds], then the room reverts to its default music.
 *
 * The engine drives the lifecycle by polling each tick: [pollDueLyrics] surfaces
 * lyric lines to broadcast (spread evenly across the track — flavour for players
 * without audio), then [pollExpired] removes and returns finished tracks so the
 * engine can announce the end and re-emit default music. Reads ([nowPlaying])
 * treat an expired-but-not-yet-polled track as already over. All time comes from
 * the injected [clock] (never wall-clock) so tests can drive it with `MutableClock`.
 */
class JukeboxSystem(
    private val clock: Clock,
    private val enabled: Boolean = true,
) {
    private val playing = mutableMapOf<RoomId, JukeboxNowPlaying>()

    /** Per-room count of lyric lines already handed out for the current track. */
    private val lyricsSent = mutableMapOf<RoomId, Int>()

    val isEnabled: Boolean get() = enabled

    /** The track currently playing in [roomId], or null if none (or it has ended). */
    fun nowPlaying(roomId: RoomId): JukeboxNowPlaying? {
        val current = playing[roomId] ?: return null
        if (current.endsAtMs <= clock.millis()) return null
        return current
    }

    /** The override music URL for [roomId] while a track plays, else null (use room default). */
    fun overrideMusic(roomId: RoomId): String? = nowPlaying(roomId)?.song?.url

    /** Seconds left on [nowPlaying], from the system clock. */
    fun secondsRemaining(nowPlaying: JukeboxNowPlaying): Int = nowPlaying.remainingSeconds(clock.millis())

    /**
     * Attempts to start [songIndex] (0-based) from [playlist] in [roomId] on
     * behalf of [buyerName] holding [currentGold]. On success, deducts the cost
     * via [deductGold] and records the new state.
     */
    fun play(
        roomId: RoomId,
        playlist: List<JukeboxSong>,
        songIndex: Int,
        buyerName: String,
        currentGold: Long,
        deductGold: (Long) -> Unit,
    ): JukeboxPlayResult {
        if (!enabled) return JukeboxPlayResult.Disabled
        if (playlist.isEmpty()) return JukeboxPlayResult.NoJukebox

        val current = nowPlaying(roomId)
        if (current != null) {
            return JukeboxPlayResult.Busy(current, current.remainingSeconds(clock.millis()))
        }

        val song = playlist.getOrNull(songIndex) ?: return JukeboxPlayResult.NoSuchSong(playlist.size)
        if (currentGold < song.cost) return JukeboxPlayResult.InsufficientGold(song.cost, currentGold)

        deductGold(song.cost)
        val now = clock.millis()
        val nowPlaying =
            JukeboxNowPlaying(
                song = song,
                songIndex = songIndex,
                buyerName = buyerName,
                startedAtMs = now,
                endsAtMs = now + song.durationSeconds * 1000L,
            )
        playing[roomId] = nowPlaying
        lyricsSent.remove(roomId)
        return JukeboxPlayResult.Success(nowPlaying)
    }

    /**
     * Lyric lines newly due in each playing room since the last call, in song
     * order. The engine broadcasts each line to the room. Called once per tick,
     * before [pollExpired] so a track's final lines flush ahead of its end
     * announcement.
     */
    fun pollDueLyrics(): Map<RoomId, List<String>> {
        if (playing.isEmpty()) return emptyMap()
        val now = clock.millis()
        val due = mutableMapOf<RoomId, List<String>>()
        for ((roomId, current) in playing) {
            val lyrics = current.song.lyrics
            if (lyrics.isEmpty()) continue
            val sent = lyricsSent[roomId] ?: 0
            val dueCount = current.dueLyricCount(now)
            if (dueCount > sent) {
                due[roomId] = lyrics.subList(sent, dueCount).toList()
                lyricsSent[roomId] = dueCount
            }
        }
        return due
    }

    /**
     * Removes and returns the tracks that have ended since the last call, keyed
     * by room. The engine announces each ending and re-emits the room's default
     * music to its occupants. Called once per tick.
     */
    fun pollExpired(): Map<RoomId, JukeboxNowPlaying> {
        val now = clock.millis()
        val due = playing.filterValues { it.endsAtMs <= now }
        for (roomId in due.keys) {
            playing.remove(roomId)
            lyricsSent.remove(roomId)
        }
        return due
    }

    /** Clears all state. For tests / world reloads. */
    fun clear() {
        playing.clear()
        lyricsSent.clear()
    }
}
