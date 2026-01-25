package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.World
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import kotlinx.coroutines.channels.SendChannel

class CommandRouter(
    private val world: World,
    private val players: PlayerRegistry,
    private val outbound: SendChannel<OutboundEvent>,
) {
    suspend fun handle(
        sessionId: SessionId,
        cmd: Command,
    ) {
        when (cmd) {
            Command.Noop -> {
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Help -> {
                outbound.send(OutboundEvent.SendInfo(sessionId, "Commands: help, look/l, n/s/e/w, ansi on/off, clear, colors, quit"))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Look -> {
                sendLook(sessionId)
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Move -> {
                val from = players.get(sessionId)?.roomId ?: world.startRoom
                val room = world.rooms[from] ?: return
                val to = room.exits[cmd.dir]
                if (to == null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "You can't go that way."))
                } else {
                    players.moveTo(sessionId, to)
                    sendLook(sessionId)
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Quit -> {
                outbound.send(OutboundEvent.Close(sessionId, "Goodbye!"))
            }

            Command.AnsiOn -> {
                outbound.send(OutboundEvent.SetAnsi(sessionId, true))
                outbound.send(OutboundEvent.SendInfo(sessionId, "ANSI enabled"))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.AnsiOff -> {
                outbound.send(OutboundEvent.SetAnsi(sessionId, false))
                outbound.send(OutboundEvent.SendInfo(sessionId, "ANSI disabled"))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Clear -> {
                outbound.send(OutboundEvent.ClearScreen(sessionId))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Colors -> {
                outbound.send(OutboundEvent.ShowAnsiDemo(sessionId))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Say -> {
                val me = players.get(sessionId) ?: return
                val roomId = me.roomId
                val members = players.membersInRoom(roomId)

                // Sender feedback
                outbound.send(OutboundEvent.SendText(sessionId, "You say: ${cmd.message}"))

                // Everyone else in the room
                for (other in members) {
                    if (other == sessionId) continue
                    outbound.send(OutboundEvent.SendText(other, "${me.name} says: ${cmd.message}"))
                }

                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Who -> {
                val list =
                    players
                        .allPlayers()
                        .sortedBy { it.name }
                        .joinToString(separator = ", ") { it.name }

                outbound.send(OutboundEvent.SendInfo(sessionId, "Online: $list"))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Unknown -> {
                outbound.send(OutboundEvent.SendText(sessionId, "Huh?"))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }
        }
    }

    private suspend fun sendLook(sessionId: SessionId) {
        val roomId = players.get(sessionId)?.roomId ?: world.startRoom
        val room = world.rooms[roomId] ?: return
        outbound.send(OutboundEvent.SendText(sessionId, room.title))
        outbound.send(OutboundEvent.SendText(sessionId, room.description))
        val exits = if (room.exits.isEmpty()) "none" else room.exits.keys.joinToString(", ") { it.name.lowercase() }
        outbound.send(OutboundEvent.SendInfo(sessionId, "Exits: $exits"))
    }
}
