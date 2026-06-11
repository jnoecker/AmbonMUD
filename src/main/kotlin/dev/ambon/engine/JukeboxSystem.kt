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
    /** Epoch millis at which the track stops and the room reverts to default music. */
    val endsAtMs: Long,
) {
    fun remainingSeconds(nowMs: Long): Int = (((endsAtMs - nowMs) + 999) / 1000).coerceAtLeast(0).toInt()
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
 * The engine drives reverts by polling [pollExpired] each tick; reads ([nowPlaying])
 * also expire lazily so a player entering after a song ended hears the default
 * track. All time comes from the injected [clock] (never wall-clock) so tests can
 * drive it with `MutableClock`.
 */
class JukeboxSystem(
    private val clock: Clock,
    private val enabled: Boolean = true,
) {
    private val playing = mutableMapOf<RoomId, JukeboxNowPlaying>()

    val isEnabled: Boolean get() = enabled

    /** The track currently playing in [roomId], or null if none (expiring lazily). */
    fun nowPlaying(roomId: RoomId): JukeboxNowPlaying? {
        val current = playing[roomId] ?: return null
        if (current.endsAtMs <= clock.millis()) {
            playing.remove(roomId)
            return null
        }
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
        val nowPlaying =
            JukeboxNowPlaying(
                song = song,
                songIndex = songIndex,
                buyerName = buyerName,
                endsAtMs = clock.millis() + song.durationSeconds * 1000L,
            )
        playing[roomId] = nowPlaying
        return JukeboxPlayResult.Success(nowPlaying)
    }

    /**
     * Removes and returns the rooms whose track has ended since the last call.
     * The engine re-emits each room's default music to its occupants. Called once
     * per tick.
     */
    fun pollExpired(): List<RoomId> {
        val now = clock.millis()
        val due = playing.filterValues { it.endsAtMs <= now }.keys.toList()
        due.forEach { playing.remove(it) }
        return due
    }

    /** Clears all state. For tests / world reloads. */
    fun clear() = playing.clear()
}
