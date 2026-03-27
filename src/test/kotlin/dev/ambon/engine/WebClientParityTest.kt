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

    // -- Command parity -------------------------------------------------------

    /**
     * Maps each server command key from [AppConfig.CommandPaletteConfig.Companion.defaultCommandEntries]
     * to one or more "canonical words" that should appear in the client COMMANDS array.
     *
     * The server metadata uses synthetic keys like "shop_list" and "group_invite";
     * the client uses the bare first word the player types ("list", "group").
     * This mapping bridges that gap.
     */
    private fun serverKeyToClientWords(key: String): List<String> = when (key) {
        // Navigation
        "move" -> listOf("north", "south", "east", "west", "up", "down")
        // Shops
        "shop_list" -> listOf("list")
        "balance" -> listOf("gold")
        // Quests
        "quest_log", "quest_info", "quest_abandon" -> listOf("quest")
        "accept" -> listOf("accept")
        // Groups
        "group_invite", "group_accept", "group_leave", "group_kick", "group_list" -> listOf("group")
        // Guilds
        "guild_create", "guild_disband", "guild_invite", "guild_accept",
        "guild_leave", "guild_kick", "guild_promote", "guild_demote",
        "guild_motd", "guild_roster", "guild_info",
        -> listOf("guild")
        // World interaction
        "get_from" -> listOf("get")
        "put_in" -> listOf("put")
        // Progression
        "achievements" -> listOf("achievements")
        // Utility
        "ansi" -> listOf() // ANSI toggle is not exposed in the web client (no terminal)
        // Staff commands — not in client autocomplete (gated server-side)
        "goto", "transfer", "spawn", "smite", "staff_kick", "dispel",
        "setlevel", "shutdown", "reload",
        -> listOf()
        // Default: the key itself is the word the player types
        else -> listOf(key)
    }

    @Test
    fun `every server command has a matching web client autocomplete entry`() {
        val constantsFile = repoRoot.resolve("web-v3/src/constants.ts")
        assertTrue(constantsFile.exists()) { "constants.ts not found at ${constantsFile.absolutePath}" }

        val constantsText = constantsFile.readText()

        // Extract the COMMANDS array entries: unquoted strings between the [ ] block
        val commandsBlockRegex = Regex("""COMMANDS\s*=\s*\[(.*?)];""", RegexOption.DOT_MATCHES_ALL)
        val commandsBlock = commandsBlockRegex.find(constantsText)?.groupValues?.get(1)
            ?: error("Could not find COMMANDS array in constants.ts")
        val clientCommands = Regex(""""([^"]+)"""").findAll(commandsBlock).map { it.groupValues[1] }.toSet()

        assertTrue(clientCommands.isNotEmpty()) { "Parsed zero commands from constants.ts" }

        val serverEntries = CommandsConfig.defaultCommandEntries()
        val missing = mutableListOf<String>()

        for ((key, _) in serverEntries) {
            val expectedWords = serverKeyToClientWords(key)
            for (word in expectedWords) {
                if (word !in clientCommands) {
                    missing += "$key -> expected '$word' in COMMANDS"
                }
            }
        }

        assertTrue(missing.isEmpty()) {
            "Server commands missing from web client COMMANDS array:\n  ${missing.joinToString("\n  ")}"
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

        val missing = emittedPackages.sorted().filter { it !in clientHandlers }

        assertTrue(missing.isEmpty()) {
            "Server emits GMCP packages with no handler in applyGmcpPackage.ts:\n  ${missing.joinToString("\n  ")}"
        }
    }
}
