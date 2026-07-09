package dev.ambon.metrics

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every `onGameEvent("system", "event")` literal in production code must be
 * pre-registered in [GameMetrics.KNOWN_GAME_EVENTS], or the metric is silently
 * dropped at runtime with a WARN on every emission (observed in production for
 * the entire akathavae system, which shipped without registry entries).
 *
 * Mirrors the source-scanning approach of WebClientParityTest: greps the main
 * source tree for call-site literals and asserts each pair is registered.
 * Dynamic (non-literal) event names can't be caught here — those should be
 * normalised through an enum like DisconnectReason instead.
 */
class GameEventRegistryCoverageTest {
    private val repoRoot: File = File(System.getProperty("user.dir"))

    private val callSiteRegex = Regex("""onGameEvent\(\s*"([a-z_]+)",\s*"([a-z_]+)"""")

    @Test
    fun `every onGameEvent call site is registered in KNOWN_GAME_EVENTS`() {
        val srcDir = repoRoot.resolve("src/main/kotlin")
        assertTrue(srcDir.isDirectory) { "src/main/kotlin not found from ${repoRoot.absolutePath}" }

        val emitted = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> callSiteRegex.findAll(file.readText()) }
            .map { it.groupValues[1] to it.groupValues[2] }
            .toSortedSet(compareBy({ it.first }, { it.second }))

        assertTrue(emitted.isNotEmpty()) { "expected to find onGameEvent call sites in src/main/kotlin" }

        val unregistered = emitted.filterNot { (system, event) ->
            GameMetrics.KNOWN_GAME_EVENTS[system]?.contains(event) == true
        }

        assertTrue(unregistered.isEmpty()) {
            "onGameEvent call sites missing from GameMetrics.KNOWN_GAME_EVENTS " +
                "(the metric is silently dropped at runtime): " +
                unregistered.joinToString { (s, e) -> "$s/$e" }
        }
    }

    @Test
    fun `registered events are actually emitted somewhere`() {
        val srcDir = repoRoot.resolve("src/main/kotlin")
        val emitted = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> callSiteRegex.findAll(file.readText()) }
            .map { it.groupValues[1] to it.groupValues[2] }
            .toSet()

        // Dead registry entries aren't harmful, but they mislead dashboards —
        // keep the registry honest in both directions.
        val dead = GameMetrics.KNOWN_GAME_EVENTS.flatMap { (system, events) ->
            events.map { system to it }
        }.filterNot { it in emitted }

        assertTrue(dead.isEmpty()) {
            "KNOWN_GAME_EVENTS entries with no onGameEvent call site (stale registry): " +
                dead.joinToString { (s, e) -> "$s/$e" }
        }
    }
}
