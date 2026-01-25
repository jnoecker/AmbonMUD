package dev.ambon.engine.commands

import dev.ambon.domain.world.Direction

sealed interface Command {
    data object Help : Command

    data object Look : Command

    data object Quit : Command

    data object AnsiOn : Command

    data object AnsiOff : Command

    data object Clear : Command

    data object Colors : Command

    data class Move(
        val dir: Direction,
    ) : Command

    data class Say(
        val message: String,
    ) : Command

    data object Who : Command

    data class Unknown(
        val raw: String,
    ) : Command

    data object Noop : Command
}

object CommandParser {
    fun parse(input: String): Command {
        val line = input.trim().lowercase()
        if (line.isEmpty()) return Command.Noop

        // <say hello there> or <'hello there>
        if (line.startsWith("say ")) {
            val msg = line.drop(4).trim()
            return if (msg.isEmpty()) Command.Unknown(line) else Command.Say(msg)
        }
        if (line.startsWith("'")) {
            val msg = line.drop(1).trim()
            return if (msg.isEmpty()) Command.Unknown(line) else Command.Say(msg)
        }

        return when (line) {
            "help", "?" -> Command.Help
            "look", "l" -> Command.Look
            "quit", "exit" -> Command.Quit
            "ansi on" -> Command.AnsiOn
            "ansi off" -> Command.AnsiOff
            "clear" -> Command.Clear
            "colors" -> Command.Colors
            "who" -> Command.Who
            "n", "north" -> Command.Move(Direction.NORTH)
            "s", "south" -> Command.Move(Direction.SOUTH)
            "e", "east" -> Command.Move(Direction.EAST)
            "w", "west" -> Command.Move(Direction.WEST)
            else -> Command.Unknown(line)
        }
    }
}
