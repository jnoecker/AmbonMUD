package dev.ambon.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `application.yaml` fully replaces [CommandsConfig.defaultCommandEntries] when
 * it defines `commands.entries` — a key missing from the YAML silently vanishes
 * from `help` and the web command palette, and a YAML entry that omits a field
 * (e.g. `requiresTarget`) silently downgrades the default. That drift is how the
 * auction/train/bank/rest family went undocumented for months.
 *
 * This test pins the two sources together. On mismatch it writes the
 * regenerated YAML block (rendered from the code defaults, which are the source
 * of truth) to `build/command-manifest.yaml` for copy-paste into
 * `application.yaml`.
 */
class CommandManifestSyncTest {
    private val repoRoot = File(System.getProperty("user.dir"))

    @Test
    fun `application yaml commands entries exactly match the code defaults`() {
        val yamlFile = repoRoot.resolve("src/main/resources/application.yaml")
        val entriesNode = ObjectMapper(YAMLFactory())
            .readTree(yamlFile)
            .path("ambonmud")
            .path("engine")
            .path("commands")
            .path("entries")
        require(!entriesNode.isMissingNode) { "commands.entries not found in application.yaml" }

        val yamlEntries = linkedMapOf<String, CommandMetadata>()
        for ((key, node) in entriesNode.fields()) {
            yamlEntries[key] = CommandMetadata(
                usage = node.path("usage").asText(""),
                description = node.path("description").asText(""),
                category = node.path("category").asText("general"),
                staff = node.path("staff").asBoolean(false),
                requiresTarget = node.path("requiresTarget").asBoolean(false),
            )
        }

        val defaults = CommandsConfig.defaultCommandEntries()

        // Always refresh the regeneration artifact so maintainers can copy-paste.
        val artifact = repoRoot.resolve("build/command-manifest.yaml")
        artifact.parentFile.mkdirs()
        artifact.writeText(renderYamlBlock(defaults))

        if (yamlEntries == defaults) return

        val missing = defaults.keys - yamlEntries.keys
        val extra = yamlEntries.keys - defaults.keys
        val differing = (defaults.keys intersect yamlEntries.keys)
            .filter { defaults[it] != yamlEntries[it] }
        fail<Unit>(
            buildString {
                appendLine("application.yaml commands.entries is out of sync with CommandsConfig.defaultCommandEntries().")
                if (missing.isNotEmpty()) appendLine("  Missing from YAML: ${missing.sorted()}")
                if (extra.isNotEmpty()) appendLine("  Extra in YAML: ${extra.sorted()}")
                for (key in differing) {
                    appendLine("  Differs '$key':")
                    appendLine("    default = ${defaults[key]}")
                    appendLine("    yaml    = ${yamlEntries[key]}")
                }
                appendLine("Paste the regenerated block from build/command-manifest.yaml into application.yaml.")
            },
        )
    }

    /** Renders the canonical `commands:` block at application.yaml's indentation. */
    private fun renderYamlBlock(entries: Map<String, CommandMetadata>): String = buildString {
        appendLine("    commands:")
        appendLine("      entries:")
        for ((key, m) in entries) {
            appendLine("        $key:")
            appendLine("          usage: ${quote(m.usage)}")
            if (m.description.isNotEmpty()) appendLine("          description: ${quote(m.description)}")
            appendLine("          category: ${quote(m.category)}")
            appendLine("          staff: ${m.staff}")
            if (m.requiresTarget) appendLine("          requiresTarget: true")
        }
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
