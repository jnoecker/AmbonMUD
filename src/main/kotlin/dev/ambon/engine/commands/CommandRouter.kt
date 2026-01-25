package dev.ambon.engine.commands

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.RenameResult
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
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "Commands: help, look/l, n/s/e/w, ansi on/off, clear, colors, say, who, tell, gossip, quit",
                    ),
                )
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Look -> {
                sendLook(sessionId)
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Move -> {
                val me = players.get(sessionId) ?: return
                val from = me.roomId
                val room = world.rooms[from] ?: return
                val to = room.exits[cmd.dir]
                if (to == null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "You can't go that way."))
                } else {
                    val oldMembers = players.membersInRoom(from).filter { it != sessionId }
                    for (other in oldMembers) {
                        outbound.send(OutboundEvent.SendText(other, "${me.name} leaves."))
                    }
                    players.moveTo(sessionId, to)
                    val newMembers = players.membersInRoom(to).filter { it != sessionId }
                    for (other in newMembers) {
                        outbound.send(OutboundEvent.SendText(other, "${me.name} enters."))
                    }
                    sendLook(sessionId)
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Quit -> {
                players.disconnect(sessionId)
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

            is Command.Emote -> {
                val me = players.get(sessionId) ?: return
                val roomId = me.roomId
                val members = players.membersInRoom(roomId)

                // Everyone else in the room
                for (player in members) {
                    outbound.send(OutboundEvent.SendText(player, "${me.name} ${cmd.message}"))
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

            is Command.Name -> {
                when (players.rename(sessionId, cmd.newName)) {
                    RenameResult.Ok -> {
                        outbound.send(OutboundEvent.SendInfo(sessionId, "Name set to ${players.get(sessionId)!!.name}"))
                    }

                    RenameResult.Invalid -> {
                        outbound.send(
                            OutboundEvent.SendError(
                                sessionId,
                                "Invalid name. Use 2-16 chars: letters/digits/_ and cannot start with digit.",
                            ),
                        )
                    }

                    RenameResult.Taken -> {
                        outbound.send(OutboundEvent.SendError(sessionId, "That name is already taken."))
                    }
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Tell -> {
                val me = players.get(sessionId) ?: return
                val targetSid = players.findSessionByName(cmd.target)
                if (targetSid == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "No such player: ${cmd.target}"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                if (targetSid == sessionId) {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "You tell yourself: ${cmd.message}"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                outbound.send(OutboundEvent.SendText(sessionId, "You tell ${cmd.target}: ${cmd.message}"))
                outbound.send(OutboundEvent.SendText(targetSid, "${me.name} tells you: ${cmd.message}"))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Gossip -> {
                val me = players.get(sessionId) ?: return
                for (p in players.allPlayers()) {
                    if (p.sessionId == sessionId) {
                        outbound.send(OutboundEvent.SendText(sessionId, "You gossip: ${cmd.message}"))
                    } else {
                        outbound.send(OutboundEvent.SendText(p.sessionId, "[GOSSIP] ${me.name}: ${cmd.message}"))
                    }
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Exits -> {
                val r = room(currentRoomId(sessionId))
                outbound.send(OutboundEvent.SendInfo(sessionId, exitsLine(r)))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.LookDir -> {
                val r = room(currentRoomId(sessionId))
                val targetId = r.exits[cmd.dir]
                if (targetId == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "You see nothing that way."))
                } else {
                    val target = room(targetId)
                    outbound.send(OutboundEvent.SendText(sessionId, target.title))
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Invalid -> {
                outbound.send(OutboundEvent.SendText(sessionId, "Invalid command: ${cmd.command}"))
                if (cmd.usage != null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "Usage: ${cmd.usage}"))
                } else {
                    outbound.send(OutboundEvent.SendText(sessionId, "Try 'help' for a list of commands."))
                }
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
        val roomPlayers =
            players
                .playersInRoom(roomId)
                .map { it.name }
                .sorted()
        val playersLine =
            if (roomPlayers.isEmpty()) {
                "Players here: none"
            } else {
                "Players here: " + roomPlayers.joinToString(", ")
            }
        outbound.send(OutboundEvent.SendInfo(sessionId, playersLine))
    }

    private fun currentRoomId(sessionId: SessionId): RoomId = players.get(sessionId)!!.roomId // or players.location(sessionId)

    private fun room(roomId: RoomId) = world.rooms.getValue(roomId)

    private fun exitsLine(r: Room): String =
        if (r.exits.isEmpty()) {
            "Exits: none"
        } else {
            val names =
                r.exits.keys
                    .sortedBy { it.name } // stable order; adjust if you want N,E,S,W
                    .joinToString(", ") { it.name.lowercase() }
            "Exits: $names"
        }
}
