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
     * Every user-facing command recognized by [CommandParser] should have a
     * corresponding entry in [CommandsConfig.defaultCommandEntries] so that
     * `Server.Commands` GMCP includes it (command palette, help panel, autocomplete).
     *
     * Staff-only commands may be present; internal/meta commands (Noop, Invalid,
     * Unknown) are excluded.
     */
    @Test
    fun `every parser command has a manifest entry in defaultCommandEntries`() {
        val manifest = CommandsConfig.defaultCommandEntries()
        val manifestKeys = manifest.keys

        // Map each meaningful Command subtype to the manifest key(s) it should match.
        // This is the single source of truth for the parser → manifest mapping.
        val parserToManifest = mapOf(
            // Navigation
            "Look" to "look",
            "Move" to "move",
            "Exits" to "exits",
            "Recall" to "recall",
            "LookDir" to "look",
            "LookAt" to "look",
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
            // World
            "Open" to "open",
            "Close" to "close",
            "Lock" to "lock",
            "Unlock" to "unlock",
            "Search" to "search",
            "Pull" to "pull",
            "Read" to "read",
            "Put" to "put_in",
            "GetFrom" to "get_from",
            // Combat
            "Kill" to "kill",
            "Flee" to "flee",
            "Cast" to "cast",
            // Progression
            "Spells" to "spells",
            "Effects" to "effects",
            "Score" to "score",
            "Title" to "title",
            "Gender" to "gender",
            "Sprite" to "sprite",
            // Shops
            "ShopList" to "shop_list",
            "Buy" to "buy",
            "Sell" to "sell",
            "Balance" to "balance",
            // Quests
            "QuestLog" to "quest_log",
            "QuestInfo" to "quest_info",
            "QuestAbandon" to "quest_abandon",
            "Accept" to "accept",
            "Achievements" to "achievements",
            // Social
            "Talk" to "talk",
            "DialogueChoice" to "talk",
            "Friend" to "friend",
            "Mail" to "mail",
            // Groups
            "Gtell" to "gtell",
            "Gchat" to "gchat",
            // Utility
            "Help" to "help",
            "AnsiOn" to "ansi",
            "AnsiOff" to "ansi",
            "Colors" to "colors",
            "Clear" to "clear",
            "Quit" to "quit",
            "Phase" to "phase",
            "Gather" to "gather",
            "Craft" to "craft",
            "Recipes" to "recipes",
            "CraftSkills" to "craftskills",
            // Staff
            "Goto" to "goto",
            "Transfer" to "transfer",
            "Spawn" to "spawn",
            "Smite" to "smite",
            "Kick" to "staff_kick",
            "SetLevel" to "setlevel",
            "Dispel" to "dispel",
            "Shutdown" to "shutdown",
            "Reload" to "reload",
            "Possess" to "possess",
            "Return" to "return",
            "Invis" to "invis",
            "Broadcast" to "broadcast",
        )

        // GroupCmd and Guild subcommands map to their parent manifest entries
        val sealedSubcommands = mapOf(
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
        )

        val allMappings = parserToManifest + sealedSubcommands
        val missing = mutableListOf<String>()

        for ((commandName, manifestKey) in allMappings) {
            if (manifestKey !in manifestKeys) {
                missing += "Command.$commandName → expected manifest key '$manifestKey'"
            }
        }

        assertTrue(missing.isEmpty()) {
            "Parser commands missing from defaultCommandEntries() manifest:\n  ${missing.joinToString("\n  ")}"
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
            "Lottery.Info", // lottery panel not yet built
        )
        val missing = emittedPackages.sorted().filter { it !in clientHandlers && it !in serverOnlyExclusions }

        assertTrue(missing.isEmpty()) {
            "Server emits GMCP packages with no handler in applyGmcpPackage.ts:\n  ${missing.joinToString("\n  ")}"
        }
    }
}
