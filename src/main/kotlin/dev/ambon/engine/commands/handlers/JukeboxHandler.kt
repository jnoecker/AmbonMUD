package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.JukeboxSong
import dev.ambon.engine.JukeboxNowPlaying
import dev.ambon.engine.JukeboxPlayResult
import dev.ambon.engine.JukeboxQueueResult
import dev.ambon.engine.JukeboxSystem
import dev.ambon.engine.PlayerState
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

/**
 * The room jukebox: players pay a few gold to play an authored track, which
 * becomes the room's music for everyone present until it ends (then the room
 * reverts to its default music — see [JukeboxSystem]). A playing jukebox is
 * locked, but anyone *other than* the current track's buyer can pay to queue
 * the single next-up slot — the anti-monopoly rule that keeps one player from
 * chain-controlling the room's music.
 */
class JukeboxHandler(
    private val ctx: EngineContext,
    private val jukeboxSystem: JukeboxSystem,
    private val markVitalsDirty: (SessionId) -> Unit = {},
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Jukebox> { sid, _ -> handleList(sid) }
        router.on<Command.JukeboxPlay> { sid, cmd -> handlePlay(sid, cmd) }
        router.on<Command.JukeboxQueue> { sid, cmd -> handleQueue(sid, cmd) }
    }

    private suspend fun handleList(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val room = ctx.world.rooms[me.roomId]
            val playlist = room?.jukebox ?: emptyList()
            if (!jukeboxSystem.isEnabled || playlist.isEmpty()) {
                return@withPlayer sendErrorWithFeedback(
                    sessionId,
                    outbound,
                    gmcpEmitter,
                    "There is no jukebox here.",
                    "jukebox",
                    code = "NO_JUKEBOX",
                )
            }

            outbound.send(OutboundEvent.SendInfo(sessionId, "[ Jukebox ]"))
            val nowPlaying = jukeboxSystem.nowPlaying(me.roomId)
            if (nowPlaying != null) {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  Now playing: \"${nowPlaying.song.title}\"${byline(nowPlaying.song)} " +
                            "(${jukeboxSystem.secondsRemaining(nowPlaying)}s left, played by ${nowPlaying.buyerName})",
                    ),
                )
            }
            val queued = jukeboxSystem.queuedSong(me.roomId)
            if (queued != null) {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  Up next: \"${queued.song.title}\"${byline(queued.song)} (queued by ${queued.buyerName})",
                    ),
                )
            }
            playlist.forEachIndexed { index, song ->
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  ${index + 1}. \"${song.title}\"${byline(song)} — ${song.cost} gold" +
                            (song.description?.let { " — $it" } ?: ""),
                    ),
                )
            }
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  Use 'jukebox play <number>' to pay for a song, or 'jukebox queue <number>' while one plays.",
                ),
            )

            ctx.emitJukeboxGmcp(sessionId)
        }
    }

    private suspend fun handlePlay(sessionId: SessionId, cmd: Command.JukeboxPlay) {
        players.withPlayer(sessionId) { me ->
            val room = ctx.world.rooms[me.roomId]
            val playlist = room?.jukebox ?: emptyList()

            val result =
                jukeboxSystem.play(
                    roomId = me.roomId,
                    playlist = playlist,
                    songIndex = cmd.song - 1,
                    buyerName = me.name,
                    currentGold = me.gold,
                    deductGold = { cost -> me.gold -= cost },
                )

            when (result) {
                is JukeboxPlayResult.Success -> {
                    ctx.metrics.onGameEvent("jukebox", "play")
                    markVitalsDirty(sessionId)
                    announceTrackStart(me, result.nowPlaying, command = "play")
                }

                is JukeboxPlayResult.Busy -> {
                    val hint = if (result.current.buyerName == me.name) {
                        "Your song is playing — someone else gets to queue the next one."
                    } else {
                        "Use 'jukebox queue ${cmd.song}' to line it up next."
                    }
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "The jukebox is busy — \"${result.current.song.title}\" has " +
                            "${result.remainingSeconds}s left. $hint",
                        "jukebox",
                        code = "BUSY",
                        command = "play",
                    )
                }

                is JukeboxPlayResult.InsufficientGold -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "You need ${result.need} gold but only have ${result.have}.",
                        "jukebox",
                        code = "INSUFFICIENT_GOLD",
                        command = "play",
                    )
                }

                is JukeboxPlayResult.NoSuchSong -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "There is no song #${cmd.song}. This jukebox has ${result.count} song(s).",
                        "jukebox",
                        code = "NO_SUCH_SONG",
                        command = "play",
                    )
                }

                JukeboxPlayResult.NoJukebox,
                JukeboxPlayResult.Disabled,
                ->
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "There is no jukebox here.",
                        "jukebox",
                        code = "NO_JUKEBOX",
                        command = "play",
                    )
            }
        }
    }

    private suspend fun handleQueue(sessionId: SessionId, cmd: Command.JukeboxQueue) {
        players.withPlayer(sessionId) { me ->
            val room = ctx.world.rooms[me.roomId]
            val playlist = room?.jukebox ?: emptyList()

            val result =
                jukeboxSystem.queue(
                    roomId = me.roomId,
                    playlist = playlist,
                    songIndex = cmd.song - 1,
                    buyerName = me.name,
                    currentGold = me.gold,
                    deductGold = { cost -> me.gold -= cost },
                )

            when (result) {
                is JukeboxQueueResult.Queued -> {
                    val song = result.entry.song
                    ctx.metrics.onGameEvent("jukebox", "queue")
                    markVitalsDirty(sessionId)

                    val message =
                        "You drop ${song.cost} gold into the jukebox; \"${song.title}\"${byline(song)} will play next."
                    outbound.send(OutboundEvent.SendInfo(sessionId, message))
                    sendScopedFeedback(
                        sessionId,
                        gmcpEmitter,
                        "success",
                        message,
                        "jukebox",
                        code = "QUEUED",
                        command = "queue",
                    )
                    broadcastToRoomExcept(
                        me.roomId,
                        sessionId,
                        "${me.name} feeds the jukebox; \"${song.title}\"${byline(song)} will play next.",
                        players,
                        outbound,
                    )
                    // Refresh everyone's panel so the queued badge appears room-wide.
                    broadcastJukeboxState(me.roomId)
                }

                is JukeboxQueueResult.StartedInstead -> {
                    ctx.metrics.onGameEvent("jukebox", "play")
                    markVitalsDirty(sessionId)
                    announceTrackStart(me, result.nowPlaying, command = "queue")
                }

                is JukeboxQueueResult.OwnSongPlaying -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "Your song \"${result.current.song.title}\" is playing — someone else gets to pick the next one.",
                        "jukebox",
                        code = "OWN_SONG_PLAYING",
                        command = "queue",
                    )
                }

                is JukeboxQueueResult.AlreadyQueued -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "\"${result.entry.song.title}\" is already queued next (by ${result.entry.buyerName}).",
                        "jukebox",
                        code = "QUEUE_FULL",
                        command = "queue",
                    )
                }

                is JukeboxQueueResult.InsufficientGold -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "You need ${result.need} gold but only have ${result.have}.",
                        "jukebox",
                        code = "INSUFFICIENT_GOLD",
                        command = "queue",
                    )
                }

                is JukeboxQueueResult.NoSuchSong -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "There is no song #${cmd.song}. This jukebox has ${result.count} song(s).",
                        "jukebox",
                        code = "NO_SUCH_SONG",
                        command = "queue",
                    )
                }

                JukeboxQueueResult.NoJukebox,
                JukeboxQueueResult.Disabled,
                ->
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "There is no jukebox here.",
                        "jukebox",
                        code = "NO_JUKEBOX",
                        command = "queue",
                    )
            }
        }
    }

    /**
     * Announces [nowPlaying] starting this instant, paid by [me]: feedback to the
     * buyer, room broadcasts (including the song's flavour description), a
     * `Jukebox.Info` push so every web client's audio swaps, and inline music
     * links for non-web clients (see PlayerState.audioLinksEnabled).
     */
    private suspend fun announceTrackStart(me: PlayerState, nowPlaying: JukeboxNowPlaying, command: String) {
        val song = nowPlaying.song
        val message =
            "You drop ${song.cost} gold into the jukebox; it begins to play \"${song.title}\"${byline(song)}."
        outbound.send(OutboundEvent.SendInfo(me.sessionId, message))
        sendScopedFeedback(
            me.sessionId,
            gmcpEmitter,
            "success",
            message,
            "jukebox",
            code = "PLAYING",
            command = command,
        )
        broadcastToRoomExcept(
            me.roomId,
            me.sessionId,
            "${me.name} feeds the jukebox; it begins to play \"${song.title}\"${byline(song)}.",
            players,
            outbound,
        )

        // Describe the song to everyone present — flavour for players without audio.
        song.description?.let { description ->
            broadcastToRoom(me.roomId, description, players, outbound)
        }

        broadcastJukeboxState(me.roomId)

        // Inline music links for non-web clients (see PlayerState.audioLinksEnabled),
        // so their audio swaps along with the GMCP push above.
        for (occupant in players.playersInRoom(me.roomId)) {
            if (occupant.audioLinksEnabled && song.url != occupant.lastEmittedMusicUrl) {
                occupant.lastEmittedMusicUrl = song.url
                outbound.send(OutboundEvent.SendInfo(occupant.sessionId, "[music] ${song.url}"))
            }
        }
    }

    /** Pushes the room's full jukebox state (playlist + now playing + queue) to everyone present. */
    private suspend fun broadcastJukeboxState(roomId: RoomId) {
        val emitter = gmcpEmitter ?: return
        val playlist = ctx.world.rooms[roomId]?.jukebox ?: emptyList()
        val nowPlaying = jukeboxSystem.nowPlaying(roomId)
        val payload =
            emitter.buildJukeboxInfo(
                playlist,
                nowPlaying,
                nowPlaying?.let { jukeboxSystem.secondsRemaining(it) } ?: 0,
                queued = jukeboxSystem.queuedSong(roomId),
            )
        emitter.broadcastJukeboxInfo(roomId, payload, players)
    }

    /** " by <artist>" when the song names one, else "". */
    private fun byline(song: JukeboxSong): String = song.artist?.let { " by $it" } ?: ""
}
