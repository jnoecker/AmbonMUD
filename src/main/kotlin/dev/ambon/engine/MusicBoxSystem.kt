package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.MusicBox
import java.time.Clock

/**
 * A player's currently-playing music-box song, and until when. Player-scoped: the
 * song follows the player out of [roomId] until it ends or they stop it.
 */
data class MusicBoxNowPlaying(
    val box: MusicBox,
    /** The room whose box this song came from (for re-identification / lore). */
    val roomId: RoomId,
    /** Epoch millis at which the song started playing. */
    val startedAtMs: Long,
    /** Epoch millis at which the song ends. */
    val endsAtMs: Long,
) {
    fun remainingSeconds(nowMs: Long): Int = (((endsAtMs - nowMs) + 999) / 1000).coerceAtLeast(0).toInt()
}

/**
 * Tracks each *player's* currently-playing music-box song. State is transient (in
 * memory, reset on restart). Unlike the room-scoped [JukeboxSystem], a music box
 * is free and personal: starting a song replaces whatever that player had playing,
 * and the song keeps going as they move between rooms until its authored
 * [MusicBox.durationSeconds] elapses or they [stop] it.
 *
 * Lyric display (scrolling on the open device, 🎵 toasts while closed) is computed
 * entirely on the client from the song's start time, duration, and lyric list, so
 * this system only owns the play/stop/expire lifecycle. The engine polls
 * [pollExpired] each tick to clear finished songs (and re-emit a cleared
 * `MusicBox.Info` so the client reverts to room music). All time comes from the
 * injected [clock] (never wall-clock) so tests can drive it with `MutableClock`.
 */
class MusicBoxSystem(
    private val clock: Clock,
    private val enabled: Boolean = true,
) {
    /** Per-player now-playing, keyed by character name. */
    private val playing = mutableMapOf<String, MusicBoxNowPlaying>()

    val isEnabled: Boolean get() = enabled

    /** The song [player] is currently playing, or null if none (or it has ended). */
    fun nowPlaying(player: String): MusicBoxNowPlaying? {
        val current = playing[player] ?: return null
        if (current.endsAtMs <= clock.millis()) return null
        return current
    }

    /** Seconds left on [nowPlaying], from the injected clock (never negative). */
    fun secondsRemaining(nowPlaying: MusicBoxNowPlaying): Int = nowPlaying.remainingSeconds(clock.millis())

    /**
     * Starts [box] (from [roomId]) playing for [player], replacing any song they
     * already had going. Returns the new state, or null if the feature is disabled.
     */
    fun play(
        player: String,
        box: MusicBox,
        roomId: RoomId,
    ): MusicBoxNowPlaying? {
        if (!enabled) return null
        val now = clock.millis()
        val nowPlaying =
            MusicBoxNowPlaying(
                box = box,
                roomId = roomId,
                startedAtMs = now,
                endsAtMs = now + box.durationSeconds * 1000L,
            )
        playing[player] = nowPlaying
        return nowPlaying
    }

    /** Stops [player]'s song. Returns the song that was playing, or null if none. */
    fun stop(player: String): MusicBoxNowPlaying? {
        val current = nowPlaying(player)
        playing.remove(player)
        return current
    }

    /**
     * Removes songs that have ended since the last call, returning the
     * `(playerName, endedSong)` pairs so the engine can clear each player's
     * music-box state on the client. Called once per tick; cheap when idle.
     */
    fun pollExpired(): List<Pair<String, MusicBoxNowPlaying>> {
        val now = clock.millis()
        val ended = playing.filterValues { it.endsAtMs <= now }
        if (ended.isEmpty()) return emptyList()
        ended.keys.forEach { playing.remove(it) }
        return ended.map { it.key to it.value }
    }

    /** Forgets [player]'s song (e.g. on logout/disconnect). */
    fun clearPlayer(player: String) {
        playing.remove(player)
    }

    /** Clears all state. For tests / world reloads. */
    fun clear() {
        playing.clear()
    }
}
