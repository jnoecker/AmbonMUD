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

    data object Effects : Command

    data class Dispel(
        val target: String,
    ) : Command

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

    data class Talk(
        val target: String,
    ) : Command

    data class DialogueChoice(
        val optionNumber: Int,
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
        requiredArg(line, listOf("say"), "say <message>", { Command.Say(it) })?.let { return it }

        // emote: "emote <msg>"
        requiredArg(line, listOf("emote"), "emote <message>", { Command.Emote(it) })?.let { return it }

        // gossip: "gossip <msg>" or "gs <msg>"
        requiredArg(line, listOf("gossip", "gs"), "gossip <msg> or gs <msg>", { Command.Gossip(it) })?.let { return it }

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
        requiredArg(line, listOf("wear", "equip"), "wear <item>", { Command.Wear(it) })?.let { return it }

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
        requiredArg(line, listOf("get", "take", "pickup", "pick", "pick up"), "get <item>", { Command.Get(it) })?.let { return it }

        // drop
        requiredArg(line, listOf("drop"), "drop <item>", { Command.Drop(it) })?.let { return it }

        // use
        requiredArg(line, listOf("use"), "use <item>", { Command.Use(it) })?.let { return it }

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
        requiredArg(line, listOf("shout", "sh"), "shout <message>", { Command.Shout(it) })?.let { return it }

        // ooc: "ooc <msg>"
        requiredArg(line, listOf("ooc"), "ooc <message>", { Command.Ooc(it) })?.let { return it }

        // pose: "pose <msg>" or "po <msg>"
        requiredArg(line, listOf("pose", "po"), "pose <message>", { Command.Pose(it) })?.let { return it }

        // dispel (staff)
        requiredArg(line, listOf("dispel"), "dispel <target>", { Command.Dispel(it) })?.let { return it }

        // buy
        requiredArg(line, listOf("buy", "purchase"), "buy <item>", { Command.Buy(it) })?.let { return it }

        // sell
        requiredArg(line, listOf("sell"), "sell <item>", { Command.Sell(it) })?.let { return it }

        // talk
        requiredArg(line, listOf("talk"), "talk <npc>", { Command.Talk(it) })?.let { return it }

        // cast / c
        matchPrefix(line, listOf("cast", "c")) { rest ->
            if (rest.isEmpty()) return@matchPrefix Command.Invalid(line, "cast <spell> [target]")
            val parts = rest.split(Regex("\\s+"), limit = 2)
            val spellName = parts[0]
            val target = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            Command.Cast(spellName, target)
        }?.let { return it }

        // kill
        requiredArg(line, listOf("kill"), "kill <mob>", { Command.Kill(it) })?.let { return it }

        // goto
        requiredArg(line, listOf("goto"), "goto <zone:room | room | zone:>", { Command.Goto(it) })?.let { return it }

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
        requiredArg(line, listOf("spawn"), "spawn <mob-template>", { Command.Spawn(it) })?.let { return it }

        // smite
        requiredArg(line, listOf("smite"), "smite <player|mob>", { Command.Smite(it) })?.let { return it }

        // kick
        requiredArg(line, listOf("kick"), "kick <player>", { Command.Kick(it) })?.let { return it }

        // phase/layer — switch zone instance
        matchPrefix(line, listOf("phase", "layer")) { rest ->
            Command.Phase(rest.trim().ifEmpty { null })
        }?.let { return it }

        // Bare number → dialogue choice (CommandRouter decides if applicable)
        lower.toIntOrNull()?.let { n ->
            if (n in 1..9) return Command.DialogueChoice(n)
        }

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
            "effects", "buffs", "debuffs" -> Command.Effects
            "shutdown" -> Command.Shutdown
            "gold", "balance", "wealth" -> Command.Balance
            "list", "shop" -> Command.ShopList
            else -> Command.Unknown(line)
        }
    }

    /** Matches [aliases] prefix; returns Invalid(usage) if rest is blank, else [ctor](rest). */
    private inline fun requiredArg(
        line: String,
        aliases: List<String>,
        usage: String,
        ctor: (String) -> Command,
    ): Command? =
        matchPrefix(line, aliases) { rest ->
            if (rest.isEmpty()) Command.Invalid(line, usage) else ctor(rest)
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
