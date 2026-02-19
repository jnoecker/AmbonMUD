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

    data class LookDir(
        val dir: Direction,
    ) : Command

    data object Exits : Command

    data class Say(
        val message: String,
    ) : Command

    data class Emote(
        val message: String,
    ) : Command

    data object Who : Command

    data class Tell(
        val target: String,
        val message: String,
    ) : Command

    data class Gossip(
        val message: String,
    ) : Command

    data class Invalid(
        val command: String,
        val usage: String?,
    ) : Command

    data class Get(
        val keyword: String,
    ) : Command

    data class Drop(
        val keyword: String,
    ) : Command

    data object Inventory : Command

    data class Unknown(
        val raw: String,
    ) : Command

    data object Noop : Command
}

object CommandParser {
    fun parse(input: String): Command {
        val line = input.trim()
        if (line.isEmpty()) return Command.Noop

        val lower = line.lowercase()

        // <say hello there> or <'hello there>
        if (line.startsWith("'")) {
            val msg = line.drop(1).trim()
            return if (msg.isEmpty()) Command.Invalid(line, "'<message>") else Command.Say(msg)
        }

        // say: "say <msg>"
        matchPrefix(line, listOf("say")) { rest ->
            if (rest.isEmpty()) Command.Invalid(line, "say <message>") else Command.Say(rest)
        }?.let { return it }

        // say: "emote <msg>"
        matchPrefix(line, listOf("emote")) { rest ->
            if (rest.isEmpty()) Command.Invalid(line, "emote <message>") else Command.Emote(rest)
        }?.let { return it }

        // gossip: "gossip <msg>" or "gs <msg>"
        matchPrefix(line, listOf("gossip", "gs")) { rest ->
            if (rest.isEmpty()) Command.Invalid(line, "gossip <msg> or gs <msg>") else Command.Gossip(rest)
        }?.let { return it }

        // tell: "tell <target> <msg>" or "t <target> <msg>"
        matchPrefix(line, listOf("tell", "t")) { rest ->
            val parts = rest.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) return@matchPrefix Command.Invalid(line, "tell <target> <msg>")

            val target = parts[0]
            val msg = parts[1].trim()
            if (msg.isEmpty()) Command.Unknown(line) else Command.Tell(target, msg)
        }?.let { return it }

        // look <dir> / l <dir>
        matchPrefix(
            line = line,
            aliases = listOf("look", "l"),
        ) { rest ->
            if (rest.isBlank()) return@matchPrefix null
            val dir =
                parseDirectionOrNull(rest.trim())
                    ?: return@matchPrefix Command.Invalid(line, "Usage: look <direction> (e.g., look north)")
            Command.LookDir(dir)
        }?.let { return it }

        // inventory aliases
        matchPrefix(line, listOf("inventory", "inv", "i")) { rest ->
            if (rest.isNotEmpty()) Command.Invalid(line, "inventory") else Command.Inventory
        }?.let { return it }

        // get/take
        matchPrefix(line, listOf("get", "take", "pickup", "pick", "pick up")) { rest ->
            val kw = rest.trim()
            if (kw.isEmpty()) Command.Invalid(line, "get <item>") else Command.Get(kw)
        }?.let { return it }

        // drop
        matchPrefix(line, listOf("drop")) { rest ->
            val kw = rest.trim()
            if (kw.isEmpty()) Command.Invalid(line, "drop <item>") else Command.Drop(kw)
        }?.let { return it }

        return when (lower) {
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
            "exits", "ex" -> Command.Exits
            else -> Command.Unknown(line)
        }
    }

    private inline fun matchPrefix(
        line: String,
        aliases: List<String>,
        build: (rest: String) -> Command?,
    ): Command? {
        val lower = line.lowercase().trim()
        for (kw in aliases) {
            val key = kw.lowercase().trim()
            val prefix = "$key "
            if (lower.startsWith(prefix)) {
                val rest = line.drop(prefix.length).trim()
                return build(rest)
            } else if (lower == key) {
                return build("")
            }
        }
        return null
    }

    private fun parseDirectionOrNull(s: String): Direction? =
        when (s.lowercase()) {
            "n", "north" -> Direction.NORTH
            "s", "south" -> Direction.SOUTH
            "e", "east" -> Direction.EAST
            "w", "west" -> Direction.WEST
            "u", "up" -> Direction.UP
            "d", "down" -> Direction.DOWN
            else -> null
        }
}
