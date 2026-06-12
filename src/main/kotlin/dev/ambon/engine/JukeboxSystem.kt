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

/** A paid-for track waiting its turn; it starts the moment the current track ends. */
data class JukeboxQueuedSong(
    val song: JukeboxSong,
    /** 0-based index into the room's playlist. */
    val songIndex: Int,
    /** Name of the player who paid to queue it. */
    val buyerName: String,
)

/** What changed in a room when [JukeboxSystem.pollExpired] retired a track. */
data class JukeboxTransition(
    /** The track that just finished. */
    val ended: JukeboxNowPlaying,
    /** The queued successor that took over, or null if the room reverts to default music. */
    val started: JukeboxNowPlaying?,
)

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

/** Outcome of a `jukebox queue` attempt. */
sealed interface JukeboxQueueResult {
    /** The track was paid for and queued; it starts when [current] ends. */
    data class Queued(
        val entry: JukeboxQueuedSong,
        val current: JukeboxNowPlaying,
    ) : JukeboxQueueResult

    /** Nothing was playing, so the track was paid for and started immediately. */
    data class StartedInstead(
        val nowPlaying: JukeboxNowPlaying,
    ) : JukeboxQueueResult

    /** The jukebox feature is disabled server-wide. */
    data object Disabled : JukeboxQueueResult

    /** This room has no jukebox playlist. */
    data object NoJukebox : JukeboxQueueResult

    /** The requested song number was out of range; [count] songs are available. */
    data class NoSuchSong(
        val count: Int,
    ) : JukeboxQueueResult

    /** The player cannot afford the track. */
    data class InsufficientGold(
        val need: Long,
        val have: Long,
    ) : JukeboxQueueResult

    /** The requester's own song is playing — they can't also claim the next slot. */
    data class OwnSongPlaying(
        val current: JukeboxNowPlaying,
    ) : JukeboxQueueResult

    /** The single queue slot is already taken by [entry]. */
    data class AlreadyQueued(
        val entry: JukeboxQueuedSong,
    ) : JukeboxQueueResult
}

/**
 * Tracks each room's currently-playing jukebox track plus a single queued
 * successor. State is transient (in memory, reset on restart) — a paid song
 * locks the room for its authored [JukeboxSong.durationSeconds], then the
 * queued track (if any) takes over, otherwise the room reverts to its default
 * music.
 *
 * Queueing is the anti-monopoly valve: while a track plays, any player *except
 * the one who paid for it* can pay to reserve the single next-up slot ([queue]),
 * so whoever is quickest on the draw can't chain-control the room's music.
 *
 * The engine drives the lifecycle by polling each tick: [pollDueLyrics] surfaces
 * lyric lines to broadcast (spread evenly across the track — flavour for players
 * without audio), then [pollExpired] removes finished tracks and promotes their
 * queued successors, returning a [JukeboxTransition] per room so the engine can
 * announce the change and re-emit music. Reads ([nowPlaying]) treat an
 * expired-but-not-yet-polled track as already over. All time comes from the
 * injected [clock] (never wall-clock) so tests can drive it with `MutableClock`.
 */
class JukeboxSystem(
    private val clock: Clock,
    private val enabled: Boolean = true,
) {
    private val playing = mutableMapOf<RoomId, JukeboxNowPlaying>()

    /** Per-room queued successor track — a single slot (see [queue]). */
    private val queuedSongs = mutableMapOf<RoomId, JukeboxQueuedSong>()

    /** Per-room count of lyric lines already handed out for the current track. */
    private val lyricsSent = mutableMapOf<RoomId, Int>()

    val isEnabled: Boolean get() = enabled

    /** The track currently playing in [roomId], or null if none (or it has ended). */
    fun nowPlaying(roomId: RoomId): JukeboxNowPlaying? {
        val current = playing[roomId] ?: return null
        if (current.endsAtMs <= clock.millis()) return null
        return current
    }

    /** The track queued to play next in [roomId], or null if the slot is free. */
    fun queuedSong(roomId: RoomId): JukeboxQueuedSong? = queuedSongs[roomId]

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
        // A lapsed track with a queued successor still owns the box for the tick
        // gap until pollExpired promotes it — don't let a play jump the queue.
        val lapsed = playing[roomId]
        if (lapsed != null && queuedSongs.containsKey(roomId)) {
            return JukeboxPlayResult.Busy(lapsed, 0)
        }

        val song = playlist.getOrNull(songIndex) ?: return JukeboxPlayResult.NoSuchSong(playlist.size)
        if (currentGold < song.cost) return JukeboxPlayResult.InsufficientGold(song.cost, currentGold)

        deductGold(song.cost)
        return JukeboxPlayResult.Success(start(roomId, song, songIndex, buyerName))
    }

    /**
     * Attempts to queue [songIndex] (0-based) from [playlist] to play right after
     * the current track in [roomId]. One slot per room, and the buyer of the
     * *playing* track can't take it — that way someone else always gets the next
     * pick and one player can't chain-control the room's music. Queueing while
     * nothing plays simply starts the song. Gold is charged up front via
     * [deductGold].
     */
    fun queue(
        roomId: RoomId,
        playlist: List<JukeboxSong>,
        songIndex: Int,
        buyerName: String,
        currentGold: Long,
        deductGold: (Long) -> Unit,
    ): JukeboxQueueResult {
        if (!enabled) return JukeboxQueueResult.Disabled
        if (playlist.isEmpty()) return JukeboxQueueResult.NoJukebox

        val song = playlist.getOrNull(songIndex) ?: return JukeboxQueueResult.NoSuchSong(playlist.size)
        val current = nowPlaying(roomId)
        if (current != null && current.buyerName == buyerName) {
            return JukeboxQueueResult.OwnSongPlaying(current)
        }
        queuedSongs[roomId]?.let { return JukeboxQueueResult.AlreadyQueued(it) }
        if (currentGold < song.cost) return JukeboxQueueResult.InsufficientGold(song.cost, currentGold)

        deductGold(song.cost)
        if (current == null) {
            // Nothing playing — no need to wait, the song starts right now.
            return JukeboxQueueResult.StartedInstead(start(roomId, song, songIndex, buyerName))
        }
        val entry = JukeboxQueuedSong(song = song, songIndex = songIndex, buyerName = buyerName)
        queuedSongs[roomId] = entry
        return JukeboxQueueResult.Queued(entry, current)
    }

    /** Records [song] as playing in [roomId] starting now, resetting lyric progress. */
    private fun start(
        roomId: RoomId,
        song: JukeboxSong,
        songIndex: Int,
        buyerName: String,
    ): JukeboxNowPlaying {
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
        return nowPlaying
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
     * Removes the tracks that have ended since the last call and promotes each
     * room's queued successor (if any), returning a [JukeboxTransition] per room.
     * The engine announces each ending — and the takeover, when
     * [JukeboxTransition.started] is non-null — then re-emits music to the room's
     * occupants. Called once per tick.
     */
    fun pollExpired(): Map<RoomId, JukeboxTransition> {
        val now = clock.millis()
        val due = playing.filterValues { it.endsAtMs <= now }
        if (due.isEmpty()) return emptyMap()
        val transitions = mutableMapOf<RoomId, JukeboxTransition>()
        for ((roomId, ended) in due) {
            playing.remove(roomId)
            lyricsSent.remove(roomId)
            val next = queuedSongs.remove(roomId)
            val started = next?.let { start(roomId, it.song, it.songIndex, it.buyerName) }
            transitions[roomId] = JukeboxTransition(ended = ended, started = started)
        }
        return transitions
    }

    /** Clears all state. For tests / world reloads. */
    fun clear() {
        playing.clear()
        queuedSongs.clear()
        lyricsSent.clear()
    }
}
