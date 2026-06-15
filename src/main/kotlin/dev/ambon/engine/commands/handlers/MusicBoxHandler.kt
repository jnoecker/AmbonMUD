package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.MusicBox
import dev.ambon.engine.MusicBoxSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

/**
 * The room music box: a one-song miniature of the [JukeboxHandler] jukebox. Unlike
 * the room-wide, paid jukebox, the music box is **free** and **player-scoped** —
 * winding it up starts the song for *you* only, and it follows you out of the room
 * until it ends or you `musicbox stop` it (see [MusicBoxSystem]).
 *
 * Lyric display (scrolling on the open device, 🎵 toasts while closed) is computed
 * entirely on the client from the song's start time, duration, and lyric list — see
 * the `MusicBox.Info` GMCP payload — so this handler only drives the play/stop
 * lifecycle and its room/feedback messages.
 */
class MusicBoxHandler(
    private val ctx: EngineContext,
    private val musicBoxSystem: MusicBoxSystem,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.MusicBox> { sid, _ -> handleList(sid) }
        router.on<Command.MusicBoxPlay> { sid, _ -> handlePlay(sid) }
        router.on<Command.MusicBoxStop> { sid, _ -> handleStop(sid) }
    }

    private suspend fun handleList(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val box = ctx.world.rooms[me.roomId]?.musicBox
            val nowPlaying = musicBoxSystem.nowPlaying(me.name)
            if (!musicBoxSystem.isEnabled || (box == null && nowPlaying == null)) {
                return@withPlayer sendErrorWithFeedback(
                    sessionId,
                    outbound,
                    gmcpEmitter,
                    "There is no music box here.",
                    "musicbox",
                    code = "NO_MUSIC_BOX",
                )
            }

            outbound.send(OutboundEvent.SendInfo(sessionId, "[ Music Box ]"))
            if (box != null) {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  A music box rests here, ready to play \"${box.title}\"${byline(box)}." +
                            (box.description?.let { " $it" } ?: ""),
                    ),
                )
            }
            if (nowPlaying != null) {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  Now playing: \"${nowPlaying.box.title}\"${byline(nowPlaying.box)} " +
                            "(${musicBoxSystem.secondsRemaining(nowPlaying)}s left). Use 'musicbox stop' to close it.",
                    ),
                )
            } else if (box != null) {
                outbound.send(
                    OutboundEvent.SendInfo(sessionId, "  Use 'musicbox play' to wind it up — it's free, and it'll follow you."),
                )
            }

            ctx.emitMusicBoxGmcp(sessionId)
        }
    }

    private suspend fun handlePlay(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val box = ctx.world.rooms[me.roomId]?.musicBox
            if (!musicBoxSystem.isEnabled || box == null) {
                return@withPlayer sendErrorWithFeedback(
                    sessionId,
                    outbound,
                    gmcpEmitter,
                    "There is no music box here.",
                    "musicbox",
                    code = "NO_MUSIC_BOX",
                    command = "play",
                )
            }

            musicBoxSystem.play(me.name, box, me.roomId)
                ?: return@withPlayer sendErrorWithFeedback(
                    sessionId,
                    outbound,
                    gmcpEmitter,
                    "The music box stays silent.",
                    "musicbox",
                    code = "DISABLED",
                    command = "play",
                )

            ctx.metrics.onGameEvent("musicbox", "play")
            val message = "You wind up the music box; it begins to play \"${box.title}\"${byline(box)}."
            outbound.send(OutboundEvent.SendInfo(sessionId, message))
            sendScopedFeedback(
                sessionId,
                gmcpEmitter,
                "success",
                message,
                "musicbox",
                code = "PLAYING",
                command = "play",
            )
            // Personal flavour — the song is private, so only the player sees its description.
            box.description?.let { outbound.send(OutboundEvent.SendInfo(sessionId, it)) }
            // Quiet room note: others see the device wound up, but don't share the audio.
            broadcastToRoomExcept(
                me.roomId,
                sessionId,
                "${me.name} winds up a music box; a faint tune begins to play.",
                players,
                outbound,
            )

            ctx.emitMusicBoxGmcp(sessionId)
            // Inline music link for non-web clients (see PlayerState.audioLinksEnabled).
            if (me.audioLinksEnabled && box.url != me.lastEmittedMusicUrl) {
                me.lastEmittedMusicUrl = box.url
                outbound.send(OutboundEvent.SendInfo(sessionId, "[music] ${box.url}"))
            }
        }
    }

    private suspend fun handleStop(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val stopped = musicBoxSystem.stop(me.name)
                ?: return@withPlayer sendErrorWithFeedback(
                    sessionId,
                    outbound,
                    gmcpEmitter,
                    "You don't have a music box playing.",
                    "musicbox",
                    code = "NOT_PLAYING",
                    command = "stop",
                )

            ctx.metrics.onGameEvent("musicbox", "stop")
            val message = "You close the music box; \"${stopped.box.title}\" falls silent."
            outbound.send(OutboundEvent.SendInfo(sessionId, message))
            sendScopedFeedback(
                sessionId,
                gmcpEmitter,
                "success",
                message,
                "musicbox",
                code = "STOPPED",
                command = "stop",
            )

            // Clear the panel/audio and revert non-web clients to the room's own music.
            ctx.emitMusicBoxGmcp(sessionId)
            val roomMusic = ctx.world.rooms[me.roomId]?.music
            if (me.audioLinksEnabled && roomMusic != me.lastEmittedMusicUrl) {
                me.lastEmittedMusicUrl = roomMusic
                roomMusic?.let { outbound.send(OutboundEvent.SendInfo(sessionId, "[music] $it")) }
            }
        }
    }

    /** " by <artist>" when the box names one, else "". */
    private fun byline(box: MusicBox): String = box.artist?.let { " by $it" } ?: ""
}
