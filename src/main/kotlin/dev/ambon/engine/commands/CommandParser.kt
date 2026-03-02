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

    data object Recall : Command

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

    data class SetLevel(
        val playerName: String,
        val level: Int,
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

    data object QuestLog : Command

    data class QuestInfo(
        val nameHint: String,
    ) : Command

    data class QuestAbandon(
        val nameHint: String,
    ) : Command

    data class QuestAccept(
        val nameHint: String,
    ) : Command

    data object AchievementList : Command

    data class TitleSet(
        val titleArg: String,
    ) : Command

    data object TitleClear : Command

    sealed interface GroupCmd : Command {
        data class Invite(
            val target: String,
        ) : GroupCmd

        data object Accept : GroupCmd

        data object Leave : GroupCmd

        data class Kick(
            val target: String,
        ) : GroupCmd

        data object List : GroupCmd
    }

    data class Gtell(
        val message: String,
    ) : Command

    data class Gchat(
        val message: String,
    ) : Command

    sealed interface Guild : Command {
        data class Create(
            val name: String,
            val tag: String,
        ) : Guild

        data object Disband : Guild

        data class Invite(
            val target: String,
        ) : Guild

        data object Accept : Guild

        data object Leave : Guild

        data class Kick(
            val target: String,
        ) : Guild

        data class Promote(
            val target: String,
        ) : Guild

        data class Demote(
            val target: String,
        ) : Guild

        data class Motd(
            val message: String,
        ) : Guild

        data object Roster : Guild

        data object Info : Guild
    }

    // ---- World feature commands ----

    data class OpenFeature(
        val keyword: String,
    ) : Command

    data class CloseFeature(
        val keyword: String,
    ) : Command

    data class UnlockFeature(
        val keyword: String,
    ) : Command

    data class LockFeature(
        val keyword: String,
    ) : Command

    data class SearchContainer(
        val keyword: String,
    ) : Command

    /** Take an item from an open container: "get <item> from <container>". */
    data class GetFrom(
        val itemKeyword: String,
        val containerKeyword: String,
    ) : Command

    /** Place an item into an open container: "put <item> <container>" or "put <item> in <container>". */
    data class PutIn(
        val itemKeyword: String,
        val containerKeyword: String,
    ) : Command

    /** Toggle a lever/switch. */
    data class Pull(
        val keyword: String,
    ) : Command

    /** Read a sign. */
    data class ReadSign(
        val keyword: String,
    ) : Command

    data class Unknown(
        val raw: String,
    ) : Command

    data object Noop : Command

    sealed interface Mail : Command {
        /** `mail` or `mail list` — show inbox. */
        data object List : Mail

        /** `mail read <n>` — read message at 1-based index. */
        data class Read(
            val index: Int,
        ) : Mail

        /** `mail delete <n>` — delete message at 1-based index. */
        data class Delete(
            val index: Int,
        ) : Mail

        /** `mail send <player>` — begin composing a message to [recipientName]. */
        data class Send(
            val recipientName: String,
        ) : Mail

        /** `mail abort` — cancel an in-progress compose. */
        data object Abort : Mail
    }
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

        // get/take — supports "get <item>" and "get <item> from <container>"
        matchPrefix(line, listOf("get", "take", "pickup", "pick up", "pick")) { rest ->
            if (rest.isEmpty()) return@matchPrefix Command.Invalid(line, "get <item>  or  get <item> from <container>")
            val fromIdx = rest.lowercase().indexOf(" from ")
            if (fromIdx >= 0) {
                val itemKw = rest.substring(0, fromIdx).trim()
                val containerKw = rest.substring(fromIdx + 6).trim()
                when {
                    itemKw.isEmpty() || containerKw.isEmpty() ->
                        Command.Invalid(line, "get <item> from <container>")
                    else -> Command.GetFrom(itemKw, containerKw)
                }
            } else {
                Command.Get(rest)
            }
        }?.let { return it }

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

        // gtell: "gtell <msg>" or "gt <msg>"
        requiredArg(line, listOf("gtell", "gt"), "gtell <message>", { Command.Gtell(it) })?.let { return it }

        // gchat: "gchat <msg>" or "g <msg>"
        requiredArg(line, listOf("gchat", "g"), "gchat <message>", { Command.Gchat(it) })?.let { return it }

        // guild subcommands
        matchPrefix(line, listOf("guild")) { rest ->
            if (rest.isEmpty()) return@matchPrefix Command.Guild.Info
            val parts = rest.split(Regex("\\s+"), limit = 2)
            when (parts[0].lowercase()) {
                "create" -> {
                    val args = parts.getOrNull(1)?.trim() ?: ""
                    val tokens = args.split(Regex("\\s+"))
                    if (tokens.size < 2 || tokens.last().isBlank()) {
                        Command.Invalid(line, "guild create <name> <tag>")
                    } else {
                        val tag = tokens.last()
                        val name = tokens.dropLast(1).joinToString(" ").trim()
                        if (name.isEmpty()) Command.Invalid(line, "guild create <name> <tag>") else Command.Guild.Create(name, tag)
                    }
                }
                "disband" -> Command.Guild.Disband
                "invite" -> {
                    val target = parts.getOrNull(1)?.trim() ?: ""
                    if (target.isEmpty()) Command.Invalid(line, "guild invite <player>") else Command.Guild.Invite(target)
                }
                "accept" -> Command.Guild.Accept
                "leave" -> Command.Guild.Leave
                "kick" -> {
                    val target = parts.getOrNull(1)?.trim() ?: ""
                    if (target.isEmpty()) Command.Invalid(line, "guild kick <player>") else Command.Guild.Kick(target)
                }
                "promote" -> {
                    val target = parts.getOrNull(1)?.trim() ?: ""
                    if (target.isEmpty()) Command.Invalid(line, "guild promote <player>") else Command.Guild.Promote(target)
                }
                "demote" -> {
                    val target = parts.getOrNull(1)?.trim() ?: ""
                    if (target.isEmpty()) Command.Invalid(line, "guild demote <player>") else Command.Guild.Demote(target)
                }
                "motd" -> {
                    val msg = parts.getOrNull(1)?.trim() ?: ""
                    if (msg.isEmpty()) Command.Invalid(line, "guild motd <message>") else Command.Guild.Motd(msg)
                }
                "roster" -> Command.Guild.Roster
                "info" -> Command.Guild.Info
                else -> Command.Guild.Info
            }
        }?.let { return it }

        // group subcommands: "group invite <player>", "group accept", "group leave", etc.
        matchPrefix(line, listOf("group")) { rest ->
            if (rest.isEmpty()) return@matchPrefix Command.GroupCmd.List
            val parts = rest.split(Regex("\\s+"), limit = 2)
            when (parts[0].lowercase()) {
                "invite", "inv" -> {
                    val target = parts.getOrNull(1)?.trim() ?: ""
                    if (target.isEmpty()) Command.Invalid(line, "group invite <player>") else Command.GroupCmd.Invite(target)
                }
                "accept", "acc" -> Command.GroupCmd.Accept
                "leave" -> Command.GroupCmd.Leave
                "kick" -> {
                    val target = parts.getOrNull(1)?.trim() ?: ""
                    if (target.isEmpty()) Command.Invalid(line, "group kick <player>") else Command.GroupCmd.Kick(target)
                }
                "list" -> Command.GroupCmd.List
                else -> Command.GroupCmd.List
            }
        }?.let { return it }

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

        // open / close / unlock / lock
        requiredArg(line, listOf("open"), "open <door|container>", { Command.OpenFeature(it) })?.let { return it }
        requiredArg(line, listOf("close"), "close <door|container>", { Command.CloseFeature(it) })?.let { return it }
        requiredArg(line, listOf("unlock"), "unlock <door|container>", { Command.UnlockFeature(it) })?.let { return it }
        requiredArg(line, listOf("lock"), "lock <door|container>", { Command.LockFeature(it) })?.let { return it }

        // search <container>
        requiredArg(line, listOf("search"), "search <container>", { Command.SearchContainer(it) })?.let { return it }

        // put <item> in <container> or put <item> <container>
        matchPrefix(line, listOf("put")) { rest ->
            if (rest.isEmpty()) return@matchPrefix Command.Invalid(line, "put <item> <container>")
            val inIdx = rest.lowercase().indexOf(" in ")
            if (inIdx >= 0) {
                val itemKw = rest.substring(0, inIdx).trim()
                val containerKw = rest.substring(inIdx + 4).trim()
                if (itemKw.isEmpty() || containerKw.isEmpty()) {
                    Command.Invalid(line, "put <item> in <container>")
                } else {
                    Command.PutIn(itemKw, containerKw)
                }
            } else {
                val parts = rest.split(Regex("\\s+"), limit = 2)
                if (parts.size < 2) {
                    Command.Invalid(line, "put <item> <container>")
                } else {
                    Command.PutIn(parts[0], parts[1].trim())
                }
            }
        }?.let { return it }

        // pull <lever>
        requiredArg(line, listOf("pull"), "pull <lever>", { Command.Pull(it) })?.let { return it }

        // read <sign>
        requiredArg(line, listOf("read"), "read <sign>", { Command.ReadSign(it) })?.let { return it }

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

        // setlevel
        matchPrefix(line, listOf("setlevel")) { rest ->
            val parts = rest.trim().split(Regex("\\s+"), limit = 2)
            val levelStr = parts.getOrNull(1)?.trim()
            val level = levelStr?.toIntOrNull()
            when {
                parts[0].isBlank() || levelStr == null -> Command.Invalid(line, "setlevel <player> <level>")
                level == null -> Command.Invalid(line, "setlevel <player> <level>")
                else -> Command.SetLevel(parts[0], level)
            }
        }?.let { return it }

        // phase/layer — switch zone instance
        matchPrefix(line, listOf("phase", "layer")) { rest ->
            Command.Phase(rest.trim().ifEmpty { null })
        }?.let { return it }

        // accept: "accept <quest-name>" (for accepting quests offered by NPCs)
        requiredArg(line, listOf("accept"), "accept <quest>", { Command.QuestAccept(it) })?.let { return it }

        // quest subcommands: "quest log", "quest info <name>", "quest abandon <name>"
        // also "quests" as alias for "quest log"
        matchPrefix(line, listOf("quest", "quests")) { rest ->
            if (rest.isEmpty()) return@matchPrefix Command.QuestLog
            val parts = rest.split(Regex("\\s+"), limit = 2)
            when (parts[0].lowercase()) {
                "log", "list" -> Command.QuestLog
                "info" -> {
                    val hint = parts.getOrNull(1)?.trim() ?: ""
                    if (hint.isEmpty()) Command.Invalid(line, "quest info <quest-name>") else Command.QuestInfo(hint)
                }
                "abandon" -> {
                    val hint = parts.getOrNull(1)?.trim() ?: ""
                    if (hint.isEmpty()) Command.Invalid(line, "quest abandon <quest-name>") else Command.QuestAbandon(hint)
                }
                else -> Command.QuestLog
            }
        }?.let { return it }

        // achievements / ach
        matchPrefix(line, listOf("achievements", "achievement", "ach")) { Command.AchievementList }
            ?.let { return it }

        // mail subcommands: "mail", "mail list", "mail read <n>", "mail delete <n>",
        //                   "mail send <player>", "mail abort"
        matchPrefix(line, listOf("mail")) { rest ->
            if (rest.isEmpty()) return@matchPrefix Command.Mail.List
            val parts = rest.split(Regex("\\s+"), limit = 2)
            when (parts[0].lowercase()) {
                "list" -> Command.Mail.List
                "read" -> {
                    val n = parts.getOrNull(1)?.trim()?.toIntOrNull()
                        ?: return@matchPrefix Command.Invalid(line, "mail read <number>")
                    Command.Mail.Read(n)
                }
                "delete", "del" -> {
                    val n = parts.getOrNull(1)?.trim()?.toIntOrNull()
                        ?: return@matchPrefix Command.Invalid(line, "mail delete <number>")
                    Command.Mail.Delete(n)
                }
                "send" -> {
                    val name = parts.getOrNull(1)?.trim() ?: ""
                    if (name.isEmpty()) Command.Invalid(line, "mail send <player>") else Command.Mail.Send(name)
                }
                "abort" -> Command.Mail.Abort
                else -> Command.Invalid(line, "mail list | mail read <n> | mail delete <n> | mail send <player>")
            }
        }?.let { return it }

        // title clear / title <arg>
        matchPrefix(line, listOf("title")) { rest ->
            when {
                rest.isBlank() -> Command.Invalid(line, "title <titleName>  or  title clear")
                rest.trim().equals("clear", ignoreCase = true) -> Command.TitleClear
                else -> Command.TitleSet(rest.trim())
            }
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
            "recall" -> Command.Recall
            "score", "sc" -> Command.Score
            "spells", "abilities", "skills" -> Command.Spells
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
