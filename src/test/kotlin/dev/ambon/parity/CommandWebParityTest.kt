package dev.ambon.parity

import dev.ambon.config.CommandsConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Safety-net test that ensures the web client's command autocomplete list
 * stays in sync with the server's command metadata.
 *
 * When a new non-staff command is added to [CommandsConfig.defaultCommandEntries]
 * but not to the web client's `COMMANDS` array in `constants.ts`, this test
 * fails with an actionable message.
 *
 * Closes #692.
 */
class CommandWebParityTest {
    companion object {
        /**
         * Metadata keys whose web COMMANDS representation differs from the
         * simple key / underscore-prefix derivation rule.
         *
         * - `null` means the command is intentionally absent from web autocomplete.
         * - A string value is the expected COMMANDS entry for that metadata key.
         */
        private val SPECIAL_MAPPINGS: Map<String, String?> = mapOf(
            "move" to null, // Represented by individual direction words (north, south, …)
            "ansi" to null, // Web client always has ANSI; no autocomplete needed
            "balance" to "gold", // Web uses the "gold" alias
            "shop_list" to "list", // Web uses the "list" alias
        )
    }

    /**
     * For every non-staff command family in server metadata, assert there is a
     * corresponding entry in the web client's COMMANDS autocomplete array.
     */
    @Test
    fun `web COMMANDS list covers all non-staff command families`() {
        val webCommands = extractWebCommands()
        val metadata = CommandsConfig.defaultCommandEntries()

        val expected = mutableSetOf<String>()
        for ((key, meta) in metadata) {
            if (meta.staff) continue

            if (SPECIAL_MAPPINGS.containsKey(key)) {
                SPECIAL_MAPPINGS[key]?.let { expected.add(it) }
                continue
            }

            // Compound keys like "group_invite" → "group", "put_in" → "put"
            expected.add(key.substringBefore("_"))
        }

        val missing = expected - webCommands
        assertTrue(missing.isEmpty()) {
            "Commands in server metadata but missing from web COMMANDS array " +
                "(web-v3/src/constants.ts):\n  ${missing.sorted().joinToString(", ")}\n\n" +
                "Add them to the COMMANDS array, or add an exclusion to " +
                "SPECIAL_MAPPINGS in this test."
        }
    }

    @Test
    fun `web COMMANDS has no duplicate entries`() {
        val commands = parseWebCommandsList()
        val dupes = commands.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(dupes.isEmpty()) {
            "Duplicate entries in web COMMANDS array: ${dupes.sorted().joinToString(", ")}"
        }
    }

    private fun extractWebCommands(): Set<String> = parseWebCommandsList().toSet()

    private fun parseWebCommandsList(): List<String> {
        val source = File("web-v3/src/constants.ts").also {
            assertTrue(it.exists()) { "Cannot find web-v3/src/constants.ts — run tests from project root" }
        }.readText()

        val match = Regex(
            """export\s+const\s+COMMANDS\s*=\s*\[(.*?)]""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(source) ?: fail("Could not find COMMANDS array in constants.ts")

        return Regex(""""([^"]+)"""").findAll(match.groupValues[1])
            .map { it.groupValues[1] }
            .toList()
    }
}
