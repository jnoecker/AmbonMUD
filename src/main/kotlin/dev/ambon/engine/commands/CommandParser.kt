package dev.ambon.engine.commands

import dev.ambon.domain.items.ItemSlot
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

    data class Use(
        val keyword: String,
    ) : Command

    data class Give(
        val keyword: String,
        val playerName: String,
    ) : Command

    data object Inventory : Command

    data object Equipment : Command

    data class Wear(
        val keyword: String,
    ) : Command

    data class Remove(
        val slot: ItemSlot,
    ) : Command

    data class Kill(
        val target: String,
    ) : Command

    data object Flee : Command

    data object Score : Command

    data class Goto(
        val arg: String,
    ) : Command

    data class Transfer(
        val playerName: String,
        val arg: String,
    ) : Command

    data class Spawn(
        val templateArg: String,
    ) : Command

    data object Shutdown : Command

    data class Smite(
        val target: String,
    ) : Command

    data class Kick(
        val playerName: String,
    ) : Command

    data class Cast(
        val spellName: String,
        val target: String?,
    ) : Command

    data object Spells : Command

    data class Whisper(
        val target: String,
        val message: String,
    ) : Command

    data class Shout(
        val message: String,
    ) : Command

    data class Ooc(
        val message: String,
    ) : Command

    data class Pose(
        val message: String,
    ) : Command

    /**
     * Switch between zone instances (layers). No argument lists instances;
     * an argument targets a specific player name or instance number.
     */
    data class Phase(
        val targetHint: String?,
    ) : Command

    data object Balance : Command

    data object ShopList : Command

    data class Buy(
        val keyword: String,
    ) : Command

    data class Sell(
        val keyword: String,
    ) : Command

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

        // equipment aliases
        matchPrefix(line, listOf("equipment", "eq")) { rest ->
            if (rest.isNotEmpty()) Command.Invalid(line, "equipment") else Command.Equipment
        }?.let { return it }

        // wear/equip
        matchPrefix(line, listOf("wear", "equip")) { rest ->
            val kw = rest.trim()
            if (kw.isEmpty()) Command.Invalid(line, "wear <item>") else Command.Wear(kw)
        }?.let { return it }

        // remove/unequip
        matchPrefix(line, listOf("remove", "unequip")) { rest ->
            val token = rest.trim()
            if (token.isEmpty()) {
                return@matchPrefix Command.Invalid(line, "remove <head|body|hand>")
            }
            val slot = ItemSlot.parse(token)
            if (slot == null) Command.Invalid(line, "remove <head|body|hand>") else Command.Remove(slot)
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

        // use
        matchPrefix(line, listOf("use")) { rest ->
            val kw = rest.trim()
            if (kw.isEmpty()) Command.Invalid(line, "use <item>") else Command.Use(kw)
        }?.let { return it }

        // give
        matchPrefix(line, listOf("give")) { rest ->
            val trimmed = rest.trim()
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) {
                Command.Invalid(line, "give <item> <player>")
            } else {
                val playerName = parts.last()
                val keyword = parts.dropLast(1).joinToString(" ").trim()
                if (keyword.isEmpty()) Command.Invalid(line, "give <item> <player>") else Command.Give(keyword, playerName)
            }
        }?.let { return it }

        // whisper: "whisper <target> <msg>" or "wh <target> <msg>"
        matchPrefix(line, listOf("whisper", "wh")) { rest ->
            val parts = rest.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) return@matchPrefix Command.Invalid(line, "whisper <target> <msg>")
            val target = parts[0]
            val msg = parts[1].trim()
            if (msg.isEmpty()) Command.Invalid(line, "whisper <target> <msg>") else Command.Whisper(target, msg)
        }?.let { return it }

        // shout: "shout <msg>" or "sh <msg>"
        matchPrefix(line, listOf("shout", "sh")) { rest ->
            if (rest.isEmpty()) Command.Invalid(line, "shout <message>") else Command.Shout(rest)
        }?.let { return it }

        // ooc: "ooc <msg>"
        matchPrefix(line, listOf("ooc")) { rest ->
            if (rest.isEmpty()) Command.Invalid(line, "ooc <message>") else Command.Ooc(rest)
        }?.let { return it }

        // pose: "pose <msg>" or "po <msg>"
        matchPrefix(line, listOf("pose", "po")) { rest ->
            if (rest.isEmpty()) Command.Invalid(line, "pose <message>") else Command.Pose(rest)
        }?.let { return it }

        // buy
        matchPrefix(line, listOf("buy", "purchase")) { rest ->
            val kw = rest.trim()
            if (kw.isEmpty()) Command.Invalid(line, "buy <item>") else Command.Buy(kw)
        }?.let { return it }

        // sell
        matchPrefix(line, listOf("sell")) { rest ->
            val kw = rest.trim()
            if (kw.isEmpty()) Command.Invalid(line, "sell <item>") else Command.Sell(kw)
        }?.let { return it }

        // cast / c
        matchPrefix(line, listOf("cast", "c")) { rest ->
            if (rest.isEmpty()) return@matchPrefix Command.Invalid(line, "cast <spell> [target]")
            val parts = rest.split(Regex("\\s+"), limit = 2)
            val spellName = parts[0]
            val target = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            Command.Cast(spellName, target)
        }?.let { return it }

        // kill
        matchPrefix(line, listOf("kill")) { rest ->
            val target = rest.trim()
            if (target.isEmpty()) Command.Invalid(line, "kill <mob>") else Command.Kill(target)
        }?.let { return it }

        // goto
        matchPrefix(line, listOf("goto")) { rest ->
            if (rest.isEmpty()) Command.Invalid(line, "goto <zone:room | room | zone:>") else Command.Goto(rest)
        }?.let { return it }

        // transfer
        matchPrefix(line, listOf("transfer")) { rest ->
            val parts = rest.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2 || parts[1].isBlank()) {
                Command.Invalid(line, "transfer <player> <room>")
            } else {
                Command.Transfer(parts[0], parts[1].trim())
            }
        }?.let { return it }

        // spawn
        matchPrefix(line, listOf("spawn")) { rest ->
            if (rest.isEmpty()) Command.Invalid(line, "spawn <mob-template>") else Command.Spawn(rest)
        }?.let { return it }

        // smite
        matchPrefix(line, listOf("smite")) { rest ->
            if (rest.isEmpty()) Command.Invalid(line, "smite <player|mob>") else Command.Smite(rest)
        }?.let { return it }

        // kick
        matchPrefix(line, listOf("kick")) { rest ->
            if (rest.isEmpty()) Command.Invalid(line, "kick <player>") else Command.Kick(rest)
        }?.let { return it }

        // phase/layer — switch zone instance
        matchPrefix(line, listOf("phase", "layer")) { rest ->
            Command.Phase(rest.trim().ifEmpty { null })
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
            "u", "up" -> Command.Move(Direction.UP)
            "d", "down" -> Command.Move(Direction.DOWN)
            "exits", "ex" -> Command.Exits
            "flee" -> Command.Flee
            "score", "sc" -> Command.Score
            "spells", "abilities" -> Command.Spells
            "shutdown" -> Command.Shutdown
            "gold", "balance", "wealth" -> Command.Balance
            "list", "shop" -> Command.ShopList
            else -> Command.Unknown(line)
        }
    }

    private inline fun matchPrefix(
        line: String,
        aliases: List<String>,
        build: (rest: String) -> Command?,
    ): Command? {
        val lower = line.lowercase().trim()
        val orderedAliases = aliases.sortedByDescending { it.trim().length }
        for (kw in orderedAliases) {
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
