package dev.ambon.parity

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Safety-net test that ensures every GMCP package emitted by the server has
 * a corresponding handler in the web client, and vice versa.
 *
 * When a new GMCP package is added to the server but not the web client
 * (or vice versa), this test fails with an actionable message.
 *
 * Closes #693.
 */
class GmcpWebContractTest {
    companion object {
        /** Matches GMCP package name string literals: `"Word.Word"` or `"Word.Word.Word"`. */
        private val GMCP_PACKAGE_RE = Regex(""""([A-Z][a-zA-Z]+(?:\.[A-Z][a-zA-Z]+)+)"""")

        /**
         * Server-side source files that originate GMCP packages.
         * Infrastructure files (ProtoMapper, RedisOutboundBus) that merely relay
         * events are excluded — only files that create [OutboundEvent.GmcpData]
         * with new package strings belong here.
         */
        private val SERVER_GMCP_SOURCES = listOf(
            "src/main/kotlin/dev/ambon/engine/GmcpEmitter.kt",
            "src/main/kotlin/dev/ambon/engine/events/LoginFlowHandler.kt",
        )

        private const val CLIENT_GMCP_HANDLER = "web-v3/src/gmcp/applyGmcpPackage.ts"

        /**
         * GMCP packages emitted by the server but intentionally not (yet) handled
         * by the web client. Each entry must have a comment explaining why.
         */
        private val SERVER_ONLY_EXCLUSIONS: Set<String> = setOf(
            // Char.Sprites: web client sprite-list UI not yet implemented.
            // Server emits this for the sprite selection system (#706);
            // web handler to be added when the sprite picker panel is built.
            "Char.Sprites",
            // Guild hall info; web handler to be added when guild hall UI is built.
            "Guild.Hall",
        )

        /**
         * GMCP packages handled by the web client but not originating from the
         * scanned server source files (e.g. emitted from a different module,
         * or client-initiated). Each entry must have a comment.
         */
        private val CLIENT_ONLY_EXCLUSIONS: Set<String> = emptySet()
    }

    @Test
    fun `every server-emitted GMCP package has a web client handler`() {
        val serverPackages = extractServerPackages()
        val clientPackages = extractClientPackages()

        val unhandled = serverPackages - clientPackages - SERVER_ONLY_EXCLUSIONS
        assertTrue(unhandled.isEmpty()) {
            "GMCP packages emitted by server but not handled in web client " +
                "($CLIENT_GMCP_HANDLER):\n  ${unhandled.sorted().joinToString("\n  ")}\n\n" +
                "Add a case in applyGmcpPackage.ts, or add an exclusion to " +
                "SERVER_ONLY_EXCLUSIONS in this test with a comment."
        }
    }

    @Test
    fun `every web client GMCP handler corresponds to a server emission`() {
        val serverPackages = extractServerPackages()
        val clientPackages = extractClientPackages()

        val orphaned = clientPackages - serverPackages - CLIENT_ONLY_EXCLUSIONS
        assertTrue(orphaned.isEmpty()) {
            "GMCP packages handled by web client but never emitted by server:\n" +
                "  ${orphaned.sorted().joinToString("\n  ")}\n\n" +
                "Remove the dead handler, or add an exclusion to " +
                "CLIENT_ONLY_EXCLUSIONS in this test with a comment."
        }
    }

    @Test
    fun `web client GMCP handler has no duplicate case entries`() {
        val source = readFile(CLIENT_GMCP_HANDLER)
        val cases = Regex("""case\s+"([^"]+)"""").findAll(source)
            .map { it.groupValues[1] }
            .toList()
        val dupes = cases.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(dupes.isEmpty()) {
            "Duplicate case entries in $CLIENT_GMCP_HANDLER:\n  ${dupes.sorted().joinToString(", ")}"
        }
    }

    private fun extractServerPackages(): Set<String> =
        SERVER_GMCP_SOURCES.flatMap { path ->
            val source = readFile(path)
            GMCP_PACKAGE_RE.findAll(source).map { it.groupValues[1] }
        }.toSet()

    private fun extractClientPackages(): Set<String> {
        val source = readFile(CLIENT_GMCP_HANDLER)
        return Regex("""case\s+"([^"]+)"""").findAll(source)
            .map { it.groupValues[1] }
            .filter { '.' in it }
            .toSet()
    }

    private fun readFile(relativePath: String): String =
        File(relativePath).also {
            assertTrue(it.exists()) { "Cannot find $relativePath — run tests from project root" }
        }.readText()
}
