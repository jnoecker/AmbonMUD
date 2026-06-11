package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.JukeboxSong
import dev.ambon.engine.JukeboxPlayResult
import dev.ambon.engine.JukeboxSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

/**
 * The room jukebox: players pay a few gold to play an authored track, which
 * becomes the room's music for everyone present until it ends (then the room
 * reverts to its default music — see [JukeboxSystem]). A playing jukebox is
 * locked until its track finishes.
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
                            "(${jukeboxSystem.secondsRemaining(nowPlaying)}s left, queued by ${nowPlaying.buyerName})",
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
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Use 'jukebox play <number>' to pay for a song."))

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
                    val song = result.nowPlaying.song
                    ctx.metrics.onGameEvent("jukebox", "play")
                    markVitalsDirty(sessionId)

                    val message =
                        "You drop ${song.cost} gold into the jukebox; it begins to play \"${song.title}\"${byline(song)}."
                    outbound.send(OutboundEvent.SendInfo(sessionId, message))
                    sendScopedFeedback(
                        sessionId,
                        gmcpEmitter,
                        "success",
                        message,
                        "jukebox",
                        code = "PLAYING",
                        command = "play",
                    )
                    broadcastToRoomExcept(
                        me.roomId,
                        sessionId,
                        "${me.name} feeds the jukebox; it begins to play \"${song.title}\"${byline(song)}.",
                        players,
                        outbound,
                    )

                    // Describe the song to everyone present — flavour for players without audio.
                    song.description?.let { description ->
                        broadcastToRoom(me.roomId, description, players, outbound)
                    }

                    // Push the new track + countdown to everyone in the room so their audio swaps.
                    gmcpEmitter?.let { emitter ->
                        val payload =
                            emitter.buildJukeboxInfo(
                                playlist,
                                result.nowPlaying,
                                jukeboxSystem.secondsRemaining(result.nowPlaying),
                            )
                        emitter.broadcastJukeboxInfo(me.roomId, payload, players)
                    }
                }

                is JukeboxPlayResult.Busy -> {
                    sendErrorWithFeedback(
                        sessionId,
                        outbound,
                        gmcpEmitter,
                        "The jukebox is busy — \"${result.current.song.title}\" has " +
                            "${result.remainingSeconds}s left. Try again when it ends.",
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

    /** " by <artist>" when the song names one, else "". */
    private fun byline(song: JukeboxSong): String = song.artist?.let { " by $it" } ?: ""
}
