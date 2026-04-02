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

    data class LookAt(
        val target: String,
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

    // ---- Trade commands ----

    data class TradeRequest(
        val playerName: String,
    ) : Command

    data class TradeOffer(
        val itemKeyword: String,
    ) : Command

    data class TradeOfferGold(
        val amount: Long,
    ) : Command

    data object TradeAccept : Command

    data object TradeCancel : Command

    data object TradeStatus : Command

    // ---- Auction commands ----

    data class AuctionList(
        val filter: String?,
    ) : Command

    data class AuctionSell(
        val itemKeyword: String,
        val price: Long,
    ) : Command

    data class AuctionBuy(
        val listingId: Int,
    ) : Command

    data class AuctionCancel(
        val listingId: Int,
    ) : Command

    // ---- Duel / PvP commands ----

    data class Duel(
        val targetPlayer: String,
    ) : Command

    data object DuelAccept : Command

    data object DuelDecline : Command

    data object Reputation : Command

    // ---- Pet commands ----

    data object PetStatus : Command

    data object PetDismiss : Command

    data class PetName(
        val newName: String,
    ) : Command

    // ---- Enchanting commands ----

    data class Enchant(
        val itemKeyword: String,
        val enchantmentId: String? = null,
    ) : Command

    data object Enchantments : Command

    // ---- Bank commands ----

    sealed interface Bank : Command {
        data class DepositGold(
            val amount: Long,
        ) : Bank

        data class DepositItem(
            val keyword: String,
        ) : Bank

        data class WithdrawGold(
            val amount: Long,
        ) : Bank

        data class WithdrawItem(
            val keyword: String,
        ) : Bank

        data object Balance : Bank
    }

    data object Inventory : Command

    data object Equipment : Command

    data class Wear(
        val keyword: String,
    ) : Command

    data class Remove(
        val slot: String,
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

    data class Reload(
        val target: String?,
    ) : Command

    data class Broadcast(
        val message: String,
    ) : Command

    data class Smite(
        val target: String,
    ) : Command

    data class Kick(
        val playerName: String,
    ) : Command

    data class Possess(
        val target: String,
    ) : Command

    data object Return : Command

    data object Invis : Command

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

    data class SetGender(
        val gender: String,
    ) : Command

    sealed interface GroupCmd : Command {
        data class Invite(
            val target: String,
        ) : GroupCmd

        data object Accept : GroupCmd

        data object Decline : GroupCmd

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

        data object Decline : Guild

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

    // ---- Crafting & Gathering commands ----

    data class Gather(
        val keyword: String,
    ) : Command

    data class Craft(
        val recipeKeyword: String,
    ) : Command

    data class Recipes(
        val filter: String?,
    ) : Command

    data object CraftSkills : Command

    data class Specialize(
        val skill: String?,
    ) : Command

    // ---- Dungeon commands ----

    data class DungeonEnter(
        val templateKeyword: String,
        val difficulty: String?,
    ) : Command

    data object DungeonLeave : Command

    // ---- Sprite commands ----

    data object SpriteList : Command

    data class SpriteSet(
        val imageId: String,
    ) : Command

    data object SpriteDefault : Command

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

    sealed interface Friend : Command {
        /** `friend list` or `friend` or `friends` — show friends list. */
        data object List : Friend

        /** `friend add <name>` — add a player to friends list. */
        data class Add(
            val target: String,
        ) : Friend

        /** `friend remove <name>` — remove a player from friends list. */
        data class Remove(
            val target: String,
        ) : Friend
    }

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

    // ---- Housing commands ----

    sealed interface House : Command {
        /** `house` or `house status` — show house summary. */
        data object Status : House

        /** `house list` — show available room templates (at broker NPC). */
        data object ListTemplates : House

        /** `house buy` — purchase your initial house (at broker NPC). */
        data object Buy : House

        /** `house expand <template> <direction>` — add a room to your house. */
        data class Expand(
            val templateId: String,
            val direction: Direction,
        ) : House

        /** `house describe title <text>` — set a custom room title. */
        data class SetTitle(
            val text: String,
        ) : House

        /** `house describe desc <text>` — set a custom room description. */
        data class SetDescription(
            val text: String,
        ) : House

        /** `house invite <player>` — invite a player to your house. */
        data class Invite(
            val playerName: String,
        ) : House

        /** `house kick <player>` — kick a visitor out. */
        data class Kick(
            val playerName: String,
        ) : House

        /** `house guests` — list current visitors. */
        data object Guests : House
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

        // look <dir> / look <target> / l <dir> / l <target>
        matchPrefix(
            line = line,
            aliases = listOf("look", "l"),
        ) { rest ->
            if (rest.isBlank()) return@matchPrefix null
            val trimmed = rest.trim()
            val dir = parseDirectionOrNull(trimmed)
            if (dir != null) Command.LookDir(dir) else Command.LookAt(trimmed)
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
        requiredArg(line, listOf("remove", "unequip"), "remove <slot>", { Command.Remove(it.trim().lowercase()) })?.let { return it }

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

        matchPrefix(line, listOf("trade offer", "trade add")) { rest ->
            val trimmed = rest.trim()
            if (trimmed.isEmpty()) {
                Command.Invalid(line, "trade offer <item> OR trade offer <amount> gold")
            } else {
                val goldMatch = Regex("^(\\d+)\\s+gold$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
                if (goldMatch != null) {
                    Command.TradeOfferGold(goldMatch.groupValues[1].toLong())
                } else {
                    Command.TradeOffer(trimmed)
                }
            }
        }?.let { return it }

        matchPrefix(line, listOf("trade")) { rest ->
            val sub = rest.trim().lowercase()
            when {
                sub == "accept" || sub == "confirm" -> Command.TradeAccept
                sub == "cancel" || sub == "decline" || sub == "abort" -> Command.TradeCancel
                sub == "status" || sub.isEmpty() -> Command.TradeStatus
                else -> Command.TradeRequest(rest.trim())
            }
        }?.let { return it }

        matchPrefix(line, listOf("auction sell", "auction post")) { rest ->
            val trimmed = rest.trim()
            val lastSpace = trimmed.lastIndexOf(' ')
            if (lastSpace < 1) {
                Command.Invalid(line, "auction sell <item> <price>")
            } else {
                val priceStr = trimmed.substring(lastSpace + 1)
                val keyword = trimmed.substring(0, lastSpace).trim()
                val price = priceStr.toLongOrNull()
                if (price == null || keyword.isEmpty()) {
                    Command.Invalid(line, "auction sell <item> <price>")
                } else {
                    Command.AuctionSell(keyword, price)
                }
            }
        }?.let { return it }

        matchPrefix(line, listOf("auction buy", "auction purchase")) { rest ->
            val id = rest.trim().removePrefix("#").toIntOrNull()
            if (id == null) {
                Command.Invalid(line, "auction buy <listing #>")
            } else {
                Command.AuctionBuy(id)
            }
        }?.let { return it }

        matchPrefix(line, listOf("auction cancel", "auction remove")) { rest ->
            val id = rest.trim().removePrefix("#").toIntOrNull()
            if (id == null) {
                Command.Invalid(line, "auction cancel <listing #>")
            } else {
                Command.AuctionCancel(id)
            }
        }?.let { return it }

        matchPrefix(line, listOf("auction")) { rest ->
            val sub = rest.trim()
            if (sub.isEmpty() || sub.equals("list", ignoreCase = true)) {
                Command.AuctionList(null)
            } else {
                Command.AuctionList(sub)
            }
        }?.let { return it }

        matchPrefix(line, listOf("duel", "challenge", "pvp")) { rest ->
            val sub = rest.trim()
            when {
                sub.equals("accept", ignoreCase = true) || sub.equals("yes", ignoreCase = true) -> Command.DuelAccept
                sub.equals("decline", ignoreCase = true) || sub.equals("no", ignoreCase = true) -> Command.DuelDecline
                sub.isEmpty() -> Command.Invalid(line, "duel <player> | duel accept | duel decline")
                else -> Command.Duel(sub)
            }
        }?.let { return it }

        // whisper: "whisper <target> <msg>" or "wh <target> <msg>"
        matchPrefix(line, listOf("pet name")) { rest ->
            val name = rest.trim()
            if (name.isEmpty()) Command.Invalid(line, "pet name <new name>") else Command.PetName(name)
        }?.let { return it }

        matchPrefix(line, listOf("pet")) { rest ->
            when (rest.trim().lowercase()) {
                "", "status" -> Command.PetStatus
                "dismiss", "unsummon", "release" -> Command.PetDismiss
                else -> Command.PetStatus
            }
        }?.let { return it }

        matchPrefix(line, listOf("enchant")) { rest ->
            val trimmed = rest.trim()
            if (trimmed.isEmpty()) {
                Command.Invalid(line, "enchant <item> [enchantment]")
            } else {
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                Command.Enchant(
                    itemKeyword = parts[0],
                    enchantmentId = parts.getOrNull(1)?.trim(),
                )
            }
        }?.let { return it }

        matchPrefix(line, listOf("enchantments")) { _ ->
            Command.Enchantments
        }?.let { return it }

        matchPrefix(line, listOf("deposit")) { rest ->
            parseDepositWithdraw(rest.trim(), isDeposit = true)
        }?.let { return it }

        matchPrefix(line, listOf("withdraw")) { rest ->
            parseDepositWithdraw(rest.trim(), isDeposit = false)
        }?.let { return it }

        matchPrefix(line, listOf("bank")) { _ ->
            Command.Bank.Balance
        }?.let { return it }

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
                "decline", "reject" -> Command.Guild.Decline
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
                "decline", "reject" -> Command.GroupCmd.Decline
                "leave" -> Command.GroupCmd.Leave
                "kick" -> {
                    val target = parts.getOrNull(1)?.trim() ?: ""
                    if (target.isEmpty()) Command.Invalid(line, "group kick <player>") else Command.GroupCmd.Kick(target)
                }
                "list" -> Command.GroupCmd.List
                else -> Command.GroupCmd.List
            }
        }?.let { return it }

        // house subcommands
        matchPrefix(line, listOf("house", "home")) { rest ->
            if (rest.isEmpty()) return@matchPrefix Command.House.Status
            val parts = rest.split(Regex("\\s+"), limit = 2)
            when (parts[0].lowercase()) {
                "status", "info" -> Command.House.Status
                "list" -> Command.House.ListTemplates
                "buy", "purchase" -> Command.House.Buy
                "expand", "add" -> {
                    val args = parts.getOrNull(1)?.trim() ?: ""
                    val tokens = args.split(Regex("\\s+"), limit = 2)
                    val templateId = tokens.getOrNull(0)?.trim() ?: ""
                    val dirStr = tokens.getOrNull(1)?.trim() ?: ""
                    if (templateId.isEmpty() || dirStr.isEmpty()) {
                        Command.Invalid(line, "house expand <template> <direction>")
                    } else {
                        val dir = parseDirectionOrNull(dirStr)
                        if (dir == null) {
                            Command.Invalid(line, "house expand <template> <direction> (n/s/e/w/u/d)")
                        } else {
                            Command.House.Expand(templateId, dir)
                        }
                    }
                }
                "describe", "desc" -> {
                    val sub = parts.getOrNull(1)?.trim() ?: ""
                    val subParts = sub.split(Regex("\\s+"), limit = 2)
                    when (subParts[0].lowercase()) {
                        "title" -> {
                            val text = subParts.getOrNull(1)?.trim() ?: ""
                            if (text.isEmpty()) {
                                Command.Invalid(line, "house describe title <text>")
                            } else {
                                Command.House.SetTitle(text)
                            }
                        }
                        "desc", "description" -> {
                            val text = subParts.getOrNull(1)?.trim() ?: ""
                            if (text.isEmpty()) {
                                Command.Invalid(line, "house describe desc <text>")
                            } else {
                                Command.House.SetDescription(text)
                            }
                        }
                        else -> Command.Invalid(line, "house describe [title|desc] <text>")
                    }
                }
                "invite", "inv" -> {
                    val name = parts.getOrNull(1)?.trim() ?: ""
                    if (name.isEmpty()) {
                        Command.Invalid(line, "house invite <player>")
                    } else {
                        Command.House.Invite(name)
                    }
                }
                "kick" -> {
                    val name = parts.getOrNull(1)?.trim() ?: ""
                    if (name.isEmpty()) {
                        Command.Invalid(line, "house kick <player>")
                    } else {
                        Command.House.Kick(name)
                    }
                }
                "guests", "visitors" -> Command.House.Guests
                else -> Command.House.Status
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

        // broadcast
        requiredArg(line, listOf("broadcast"), "broadcast <message>", { Command.Broadcast(it) })?.let { return it }

        // spawn
        requiredArg(line, listOf("spawn"), "spawn <mob-template>", { Command.Spawn(it) })?.let { return it }

        // smite
        requiredArg(line, listOf("smite"), "smite <player|mob>", { Command.Smite(it) })?.let { return it }

        // kick
        requiredArg(line, listOf("kick"), "kick <player>", { Command.Kick(it) })?.let { return it }

        // possess
        requiredArg(line, listOf("possess", "switch"), "possess <mob>", { Command.Possess(it) })?.let { return it }

        // return (from possession)
        matchPrefix(line, listOf("return", "unpossess")) { _ -> Command.Return }?.let { return it }

        // invis (staff invisibility toggle)
        matchPrefix(line, listOf("invis", "invisibility")) { _ -> Command.Invis }?.let { return it }

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

        // friend subcommands: "friend", "friend list", "friend add <name>", "friend remove <name>"
        // also "friends" as alias for "friend list"
        matchPrefix(line, listOf("friend", "friends")) { rest ->
            if (rest.isEmpty()) return@matchPrefix Command.Friend.List
            val parts = rest.split(Regex("\\s+"), limit = 2)
            when (parts[0].lowercase()) {
                "list" -> Command.Friend.List
                "add" -> {
                    val name = parts.getOrNull(1)?.trim() ?: ""
                    if (name.isEmpty()) Command.Invalid(line, "friend add <player>") else Command.Friend.Add(name)
                }
                "remove", "rem", "del", "delete" -> {
                    val name = parts.getOrNull(1)?.trim() ?: ""
                    if (name.isEmpty()) Command.Invalid(line, "friend remove <player>") else Command.Friend.Remove(name)
                }
                else -> Command.Invalid(line, "friend list | friend add <player> | friend remove <player>")
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

        // gender <option>
        requiredArg(line, listOf("gender"), "gender <option>", { Command.SetGender(it) })?.let { return it }

        // sprite: "sprite", "sprite list", "sprite set <id>", "sprite default", "sprite clear", "sprites"
        matchPrefix(line, listOf("sprite", "sprites")) { rest ->
            if (rest.isEmpty()) return@matchPrefix Command.SpriteList
            val parts = rest.split(Regex("\\s+"), limit = 2)
            when (parts[0].lowercase()) {
                "list" -> Command.SpriteList
                "set" -> {
                    val id = parts.getOrNull(1)?.trim() ?: ""
                    if (id.isEmpty()) Command.Invalid(line, "sprite set <imageId>") else Command.SpriteSet(id)
                }
                "default", "clear", "auto" -> Command.SpriteDefault
                else -> Command.SpriteSet(rest.trim())
            }
        }?.let { return it }

        // Crafting & Gathering
        requiredArg(line, listOf("gather", "harvest", "mine"), "gather <node>") { Command.Gather(it) }
            ?.let { return it }
        requiredArg(line, listOf("craft", "make", "create"), "craft <recipe>") { Command.Craft(it) }
            ?.let { return it }
        matchPrefix(line, listOf("recipes", "recipe")) { rest ->
            Command.Recipes(rest.takeIf { it.isNotBlank() })
        }?.let { return it }

        matchPrefix(line, listOf("specialize", "specialise", "spec")) { rest ->
            Command.Specialize(rest.takeIf { it.isNotBlank() })
        }?.let { return it }

        matchPrefix(line, listOf("dungeon enter")) { rest ->
            if (rest.isBlank()) {
                Command.Invalid(line, "dungeon enter <name> [difficulty]")
            } else {
                val parts = rest.split("\\s+".toRegex(), 2)
                Command.DungeonEnter(parts[0], parts.getOrNull(1)?.takeIf { it.isNotBlank() })
            }
        }?.let { return it }

        // reload [world|abilities|effects|all]
        matchPrefix(line, listOf("reload")) { rest ->
            Command.Reload(rest.trim().takeIf { it.isNotBlank() })
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
            "craftskills", "professions", "prof" -> Command.CraftSkills
            "reputation", "rep", "factions", "standing", "standings" -> Command.Reputation
            "dungeon leave", "dungeon exit" -> Command.DungeonLeave
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

    private fun parseDepositWithdraw(rest: String, isDeposit: Boolean): Command {
        if (rest.isEmpty()) {
            val verb = if (isDeposit) "deposit" else "withdraw"
            return Command.Invalid("$verb", "$verb <amount> gold | $verb <item>")
        }
        // Check for "<amount> gold" pattern
        val goldMatch = Regex("^(\\d+)\\s+gold$", RegexOption.IGNORE_CASE).find(rest)
        if (goldMatch != null) {
            val amount = goldMatch.groupValues[1].toLongOrNull() ?: 0L
            return if (isDeposit) Command.Bank.DepositGold(amount) else Command.Bank.WithdrawGold(amount)
        }
        // Check for "all gold"
        if (rest.equals("all gold", ignoreCase = true)) {
            val amount = Long.MAX_VALUE // sentinel for "all"
            return if (isDeposit) Command.Bank.DepositGold(amount) else Command.Bank.WithdrawGold(amount)
        }
        // Otherwise treat as item keyword
        return if (isDeposit) Command.Bank.DepositItem(rest) else Command.Bank.WithdrawItem(rest)
    }
}
