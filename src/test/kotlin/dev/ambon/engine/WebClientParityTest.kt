package dev.ambon.engine

import dev.ambon.config.CommandsConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Contract tests that prevent the web client and server from drifting.
 *
 * 1. Every server command in the default command metadata has a corresponding
 *    entry in the web client's COMMANDS autocomplete array (constants.ts).
 * 2. Every GMCP package emitted by the server (GmcpEmitter + LoginFlowHandler)
 *    has a handler case in applyGmcpPackage.ts.
 */
class WebClientParityTest {
    /** Root of the repository, resolved from the well-known Gradle project layout. */
    private val repoRoot: File = File(System.getProperty("user.dir"))

    // -- Parser ↔ manifest parity ---------------------------------------------

    /**
     * Maps every Command subtype (by simple name; nested sealed members as
     * `Parent.Child`) to the manifest key in
     * [CommandsConfig.defaultCommandEntries] that documents it. The
     * completeness test below scans CommandParser.kt and fails when a new
     * Command is added without a row here — which forces a manifest entry too.
     */
    private val parserToManifest = mapOf(
        // Navigation
        "Look" to "look",
        "LookDir" to "look",
        "LookAt" to "look",
        "Move" to "move",
        "Run" to "run",
        "Areas" to "areas",
        "Exits" to "exits",
        "Recall" to "recall",
        "Rest" to "rest",
        "Depart" to "depart",
        // Akathavae (explorer path)
        "Pledge" to "pledge",
        "Renounce" to "renounce",
        "Illuminate" to "illuminate",
        "Arcanum" to "arcanum",
        "Wardrobe" to "wardrobe",
        // Communication
        "Say" to "say",
        "Tell" to "tell",
        "Whisper" to "whisper",
        "Gossip" to "gossip",
        "Shout" to "shout",
        "Ooc" to "ooc",
        "Emote" to "emote",
        "Pose" to "pose",
        "Who" to "who",
        // Items
        "Inventory" to "inventory",
        "Equipment" to "equipment",
        "Wear" to "wear",
        "Remove" to "remove",
        "Get" to "get",
        "Drop" to "drop",
        "Use" to "use",
        "Give" to "give",
        "QuickHeal" to "quickheal",
        "QuickMana" to "quickmana",
        // Trading
        "TradeRequest" to "trade",
        "TradeAccept" to "trade",
        "TradeCancel" to "trade",
        "TradeStatus" to "trade",
        "TradeOffer" to "trade_offer",
        "TradeOfferGold" to "trade_offer",
        "TradeRemove" to "trade_remove",
        // Auction house
        "AuctionList" to "auction",
        "AuctionSell" to "auction_sell",
        "AuctionBuy" to "auction_buy",
        "AuctionCancel" to "auction_cancel",
        // World
        "OpenFeature" to "open",
        "CloseFeature" to "close",
        "LockFeature" to "lock",
        "UnlockFeature" to "unlock",
        "SearchContainer" to "search",
        "Pull" to "pull",
        "ReadSign" to "read",
        "Answer" to "answer",
        "PutIn" to "put_in",
        "GetFrom" to "get_from",
        "DungeonEnter" to "dungeon",
        "DungeonLeave" to "dungeon",
        // Combat
        "Kill" to "kill",
        "Consider" to "consider",
        "Flee" to "flee",
        "Wimpy" to "wimpy",
        "Cast" to "cast",
        "Duel" to "duel",
        "DuelAccept" to "duel",
        "DuelDecline" to "duel",
        // Progression
        "Spells" to "spells",
        "Effects" to "effects",
        "Score" to "score",
        "Reputation" to "reputation",
        "Currencies" to "currencies",
        "TitleSet" to "title",
        "TitleClear" to "title",
        "SetGender" to "gender",
        "SpriteList" to "sprite",
        "SpriteSet" to "sprite",
        "SpriteDefault" to "sprite",
        "Describe" to "describe",
        "DescribeClear" to "describe",
        "DescribeCheck" to "describe",
        "Leaderboard" to "leaderboard",
        "Prestige" to "prestige",
        "PrestigeInfo" to "prestige",
        // Pets
        "PetStatus" to "pet",
        "PetDismiss" to "pet",
        "PetName" to "pet",
        "PetSkills" to "pet",
        "PetSkill" to "pet",
        // Shops & economy
        "ShopList" to "shop_list",
        "Buy" to "buy",
        "Sell" to "sell",
        "Balance" to "balance",
        "LotteryInfo" to "lottery",
        "LotteryBuy" to "lottery",
        "Gamble" to "gamble",
        "DiceRules" to "gamble",
        "Jukebox" to "jukebox",
        "JukeboxPlay" to "jukebox",
        "JukeboxQueue" to "jukebox",
        "MusicBox" to "musicbox",
        "MusicBoxPlay" to "musicbox",
        "MusicBoxStop" to "musicbox",
        // Quests
        "QuestLog" to "quest_log",
        "QuestInfo" to "quest_info",
        "QuestAbandon" to "quest_abandon",
        "QuestTurnIn" to "quest_turnin",
        "QuestTurnInById" to "quest_turnin",
        "QuestAccept" to "accept",
        "QuestAcceptById" to "accept",
        "QuestOffers" to "qoffers",
        "QuestAuto" to "bounty",
        "QuestAutoInfo" to "bounty_info",
        "QuestAutoAbandon" to "bounty_abandon",
        "AchievementList" to "achievements",
        "DailyQuests" to "daily",
        "WeeklyQuests" to "weekly",
        "GlobalQuestInfo" to "gquest",
        // Social
        "Talk" to "talk",
        "DialogueChoice" to "talk",
        "DialogueEnd" to "bye",
        // Groups
        "Gtell" to "gtell",
        "Gchat" to "gchat",
        // Crafting
        "Gather" to "gather",
        "Craft" to "craft",
        "Recipes" to "recipes",
        "CraftSkills" to "craftskills",
        "Specialize" to "specialize",
        "Enchant" to "enchant",
        "Enchantments" to "enchantments",
        // Utility
        "Help" to "help",
        "Claim" to "claim",
        "Time" to "time",
        "AnsiOn" to "ansi",
        "AnsiOff" to "ansi",
        "ScreenReaderOn" to "screenreader",
        "ScreenReaderOff" to "screenreader",
        "ScreenReaderToggle" to "screenreader",
        "AudioLinksOn" to "audio",
        "AudioLinksOff" to "audio",
        "AudioLinksToggle" to "audio",
        "AutolootOn" to "autoloot",
        "AutolootOff" to "autoloot",
        "AutolootStatus" to "autoloot",
        "AutopeekOn" to "autopeek",
        "AutopeekOff" to "autopeek",
        "AutopeekStatus" to "autopeek",
        "Colors" to "colors",
        "Clear" to "clear",
        "Quit" to "quit",
        "Phase" to "phase",
        // Staff
        "Goto" to "goto",
        "Transfer" to "transfer",
        "Spawn" to "spawn",
        "Smite" to "smite",
        "Kick" to "staff_kick",
        "SetLevel" to "setlevel",
        "SetStaff" to "setstaff",
        "SetGold" to "setgold",
        "SetRace" to "setrace",
        "SetClass" to "setclass",
        "StaffSetGender" to "setgender",
        "SetXp" to "setxp",
        "Heal" to "heal",
        "Pinfo" to "pinfo",
        "Dispel" to "dispel",
        "Shutdown" to "shutdown",
        "Reload" to "reload",
        "Possess" to "possess",
        "Return" to "return",
        "Invis" to "invis",
        "Broadcast" to "broadcast",
    )

    /** Nested sealed-family subcommands map to their parent manifest entries. */
    private val sealedSubcommands = mapOf(
        "GroupCmd.Invite" to "group_invite",
        "GroupCmd.Accept" to "group_accept",
        "GroupCmd.Decline" to "group_accept", // decline shares the group family
        "GroupCmd.Leave" to "group_leave",
        "GroupCmd.Kick" to "group_kick",
        "GroupCmd.List" to "group_list",
        "Guild.Create" to "guild_create",
        "Guild.Disband" to "guild_disband",
        "Guild.Invite" to "guild_invite",
        "Guild.Accept" to "guild_accept",
        "Guild.Decline" to "guild_accept", // decline shares the guild family
        "Guild.Leave" to "guild_leave",
        "Guild.Kick" to "guild_kick",
        "Guild.Promote" to "guild_promote",
        "Guild.Demote" to "guild_demote",
        "Guild.Motd" to "guild_motd",
        "Guild.Roster" to "guild_roster",
        "Guild.Info" to "guild_info",
        "Guild.Hall" to "guild_hall",
        "Guild.HallBuy" to "guild_hall",
        "Guild.HallExpand" to "guild_hall",
        "Guild.HallEnter" to "guild_hall",
        "Guild.HallLeave" to "guild_hall",
        "Bank.DepositGold" to "deposit",
        "Bank.DepositItem" to "deposit",
        "Bank.WithdrawGold" to "withdraw",
        "Bank.WithdrawItem" to "withdraw",
        "Bank.Balance" to "bank",
        "Stylist.List" to "stylist",
        "Stylist.ChangeRace" to "changerace",
        "Train.List" to "train",
        "Train.Learn" to "train",
        "Train.Unlock" to "train",
        "Train.Reset" to "train",
        "Friend.List" to "friend",
        "Friend.Add" to "friend",
        "Friend.Remove" to "friend",
        "Mail.List" to "mail",
        "Mail.Read" to "mail",
        "Mail.Delete" to "mail",
        "Mail.Send" to "mail",
        "Mail.Claim" to "mail",
        "Mail.Abort" to "mail",
        "House.Status" to "house",
        "House.ListTemplates" to "house_list",
        "House.Buy" to "house_buy",
        "House.Expand" to "house_expand",
        "House.SetTitle" to "house_describe",
        "House.SetDescription" to "house_describe",
        "House.Invite" to "house_invite",
        "House.Kick" to "house_kick",
        "House.Guests" to "house_guests",
    )

    /**
     * Parser internals and deliberately undocumented commands. `Petition` is a
     * hidden easter egg; the rest never reach a player's hands.
     */
    private val unmappedCommands = setOf("Noop", "Invalid", "Unknown", "Petition")

    /** Every mapped command must point at a real manifest key. */
    @Test
    fun `every parser command has a manifest entry in defaultCommandEntries`() {
        val manifestKeys = CommandsConfig.defaultCommandEntries().keys
        val missing = (parserToManifest + sealedSubcommands)
            .filterValues { it !in manifestKeys }
            .map { (commandName, manifestKey) -> "Command.$commandName → expected manifest key '$manifestKey'" }

        assertTrue(missing.isEmpty()) {
            "Parser commands missing from defaultCommandEntries() manifest:\n  ${missing.joinToString("\n  ")}"
        }
    }

    /**
     * Scans the Command sealed interface in CommandParser.kt and requires every
     * declared subtype to appear in the mapping (or the explicit exclusion
     * list), in both directions. Adding a Command without a manifest entry —
     * how auction/train/bank went undocumented — now fails here.
     */
    @Test
    fun `every Command subtype is mapped or explicitly excluded`() {
        val source = repoRoot
            .resolve("src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt")
            .readText()

        // Bound the scan to the Command interface body (it closes at the first
        // column-zero brace before the CommandParser object).
        val start = source.indexOf("sealed interface Command {")
        require(start >= 0) { "Command interface not found" }
        val end = source.indexOf("\n}", start)
        require(end >= 0) { "Command interface closing brace not found" }
        val body = source.substring(start, end)

        val declared = mutableSetOf<String>()
        var currentFamily: String? = null
        for (line in body.lineSequence()) {
            Regex("""^    sealed interface (\w+)""").find(line)?.let {
                currentFamily = it.groupValues[1]
                return@let
            }
            Regex("""^    data (?:class|object) (\w+)""").find(line)?.let {
                currentFamily = null
                declared += it.groupValues[1]
            }
            Regex("""^        data (?:class|object) (\w+)""").find(line)?.let {
                val family = currentFamily
                    ?: error("Nested command ${it.groupValues[1]} outside a sealed family")
                declared += "$family.${it.groupValues[1]}"
            }
        }
        require(declared.size > 100) { "Suspiciously few commands scanned (${declared.size}) — parser format changed?" }

        val mapped = parserToManifest.keys + sealedSubcommands.keys
        val unmapped = declared - mapped - unmappedCommands
        val stale = (mapped + unmappedCommands) - declared

        assertTrue(unmapped.isEmpty()) {
            "Command subtypes with no manifest mapping (add to parserToManifest/sealedSubcommands " +
                "and defaultCommandEntries):\n  ${unmapped.sorted().joinToString("\n  ")}"
        }
        assertTrue(stale.isEmpty()) {
            "Mapping entries for Command subtypes that no longer exist:\n  ${stale.sorted().joinToString("\n  ")}"
        }
    }

    // -- UI button commands → parser validity ---------------------------------

    /**
     * Every command string sent by web client UI buttons (onClick handlers)
     * should parse to a valid command, not [Command.Unknown] or [Command.Invalid].
     *
     * This catches bugs like the `decline` incident (#774) where buttons sent
     * commands the parser didn't recognize.
     */
    @Test
    fun `every web UI button command is a valid parser input`() {
        val srcDir = repoRoot.resolve("web-v3/src")
        assertTrue(srcDir.isDirectory) { "web-v3/src not found" }

        // Scan all .tsx and .ts files for command strings in sendCommand/onCommand/onFeatureAction calls
        val commandVerbs = mutableSetOf<String>()
        val tsFiles = srcDir.walkTopDown()
            .filter { it.extension == "tsx" || it.extension == "ts" }
            .filter { !it.path.contains("node_modules") }
            .toList()

        // Match: sendCommand("word ...", ...) or onCommand("word ...") or onCommand(`word ...`)
        // We extract the first word (the verb the parser sees)
        val literalPattern = Regex("""(?:sendCommand|onCommand|onFeatureAction)\(\s*"([a-z][a-z ]*?)(?:\s|")""")
        val templatePattern = Regex("""(?:sendCommand|onCommand|onFeatureAction)\(\s*`([a-z]+)\b""")

        for (file in tsFiles) {
            val text = file.readText()
            for (match in literalPattern.findAll(text)) {
                commandVerbs += match.groupValues[1].trim()
            }
            for (match in templatePattern.findAll(text)) {
                commandVerbs += match.groupValues[1]
            }
        }

        // Filter out false positives (non-command strings)
        val nonCommands = setOf("button", "container", "pointerdown", "none")
        commandVerbs.removeAll(nonCommands)

        assertTrue(commandVerbs.isNotEmpty()) { "Parsed zero command verbs from web UI" }

        // Each verb (or multi-word command) should parse to something other
        // than Unknown. Try with a dummy argument first, fall back to bare verb.
        val failures = mutableListOf<String>()
        for (verb in commandVerbs.sorted()) {
            val withArg = if (verb.contains(" ")) verb else "$verb test_arg"
            val bare = verb
            val resultWithArg = dev.ambon.engine.commands.CommandParser.parse(withArg)
            val resultBare = dev.ambon.engine.commands.CommandParser.parse(bare)
            if (resultWithArg is dev.ambon.engine.commands.Command.Unknown &&
                resultBare is dev.ambon.engine.commands.Command.Unknown
            ) {
                failures += "'$verb' → Unknown (tried '$withArg' and '$bare')"
            }
        }

        assertTrue(failures.isEmpty()) {
            "Web UI sends commands the parser doesn't recognize:\n  ${failures.joinToString("\n  ")}"
        }
    }

    // -- GMCP handler parity --------------------------------------------------

    @Test
    fun `every server GMCP package has a handler in applyGmcpPackage`() {
        val gmcpEmitterFile = repoRoot.resolve(
            "src/main/kotlin/dev/ambon/engine/GmcpEmitter.kt",
        )
        val loginFlowFile = repoRoot.resolve(
            "src/main/kotlin/dev/ambon/engine/events/LoginFlowHandler.kt",
        )
        val clientHandlerFile = repoRoot.resolve(
            "web-v3/src/gmcp/applyGmcpPackage.ts",
        )
        assertTrue(gmcpEmitterFile.exists()) { "GmcpEmitter.kt not found" }
        assertTrue(loginFlowFile.exists()) { "LoginFlowHandler.kt not found" }
        assertTrue(clientHandlerFile.exists()) { "applyGmcpPackage.ts not found" }

        // Collect every GMCP package string emitted by the server.
        // We look for patterns:  emit(..."PackageName"...)  or  emitRaw(..."PackageName"...)
        //   and  GmcpData(..., "PackageName", ...)
        val emittedPackages = mutableSetOf<String>()
        val packagePattern = Regex(""""([A-Z][a-z]+(?:\.[A-Z][a-zA-Z]+)+)"""")

        for (file in listOf(gmcpEmitterFile, loginFlowFile)) {
            val text = file.readText()
            for (match in packagePattern.findAll(text)) {
                val pkg = match.groupValues[1]
                // Skip supportCheck-only references and the Core.Supports family
                // which are protocol-level, not game data
                if (pkg.startsWith("Core.Supports")) continue
                emittedPackages += pkg
            }
        }

        assertTrue(emittedPackages.isNotEmpty()) { "Parsed zero GMCP packages from server code" }

        // Collect every case "..." handler in the client switch statement.
        val clientText = clientHandlerFile.readText()
        val casePattern = Regex("""case\s+"([^"]+)"""")
        val clientHandlers = casePattern.findAll(clientText).map { it.groupValues[1] }.toSet()

        assertTrue(clientHandlers.isNotEmpty()) { "Parsed zero case handlers from applyGmcpPackage.ts" }

        // Server-only packages intentionally not yet handled by the web client.
        val serverOnlyExclusions = setOf(
            "Char.Sprites", // sprite picker panel not yet built
        )
        val missing = emittedPackages.sorted().filter { it !in clientHandlers && it !in serverOnlyExclusions }

        assertTrue(missing.isEmpty()) {
            "Server emits GMCP packages with no handler in applyGmcpPackage.ts:\n  ${missing.joinToString("\n  ")}"
        }
    }
}
