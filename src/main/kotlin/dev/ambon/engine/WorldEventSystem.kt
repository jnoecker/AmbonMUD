package dev.ambon.engine

import dev.ambon.config.WorldEventDefinition
import dev.ambon.config.WorldEventsConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

private val log = KotlinLogging.logger {}

/**
 * Manages config-driven world events with real-world date ranges.
 *
 * Events activate and deactivate based on the current date. Other systems
 * can query [activeFlags] to check if event-specific content should be enabled.
 */
class WorldEventSystem(
    private val config: WorldEventsConfig,
    private val clock: Clock,
) {
    private val currentlyActive = mutableSetOf<String>()

    /** Returns the set of event IDs that are currently active. */
    fun activeEventIds(): Set<String> = currentlyActive.toSet()

    /** Returns definitions for all currently active events. */
    fun activeEvents(): Map<String, WorldEventDefinition> =
        currentlyActive.associateWith { config.definitions.getValue(it) }

    /** Returns all flags from all currently active events. */
    fun activeFlags(): Set<String> =
        currentlyActive.flatMap { config.definitions[it]?.flags ?: emptyList() }.toSet()

    /** Returns true if any active event has the given flag. */
    fun hasFlag(flag: String): Boolean = activeFlags().contains(flag)

    /** Returns all defined events with their current active status. */
    fun allEvents(): Map<String, Pair<WorldEventDefinition, Boolean>> =
        config.definitions.mapValues { (id, def) -> def to (id in currentlyActive) }

    /**
     * Called each engine tick (or periodically). Returns lists of events
     * that just activated or deactivated.
     */
    fun tick(): EventTickResult {
        val now = clock.instant()
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val activated = mutableListOf<String>()
        val deactivated = mutableListOf<String>()

        for ((id, def) in config.definitions) {
            val shouldBeActive = isActiveOn(def, today) && isInRecurrenceWindow(def, now.toEpochMilli())
            val wasActive = id in currentlyActive

            if (shouldBeActive && !wasActive) {
                currentlyActive.add(id)
                activated.add(id)
                log.info { "World event activated: $id (${def.displayName})" }
            } else if (!shouldBeActive && wasActive) {
                currentlyActive.remove(id)
                deactivated.add(id)
                log.info { "World event deactivated: $id (${def.displayName})" }
            }
        }

        return EventTickResult(activated, deactivated)
    }

    private fun isActiveOn(def: WorldEventDefinition, date: LocalDate): Boolean {
        if (def.startDate.isEmpty() && def.endDate.isEmpty()) return true
        val start = if (def.startDate.isNotEmpty()) LocalDate.parse(def.startDate) else LocalDate.MIN
        val end = if (def.endDate.isNotEmpty()) LocalDate.parse(def.endDate) else LocalDate.MAX
        return !date.isBefore(start) && !date.isAfter(end)
    }

    /**
     * A recurring event is active only during the first `durationMs` of each
     * `periodMs` cycle (epoch-anchored, shifted by `offsetMs`); without a
     * recurrence the date range alone governs activation.
     */
    private fun isInRecurrenceWindow(def: WorldEventDefinition, nowMs: Long): Boolean {
        val rec = def.recurrence ?: return true
        return Math.floorMod(nowMs - rec.offsetMs, rec.periodMs) < rec.durationMs
    }

    data class EventTickResult(
        val activated: List<String>,
        val deactivated: List<String>,
    ) {
        fun hasChanges(): Boolean = activated.isNotEmpty() || deactivated.isNotEmpty()
    }
}
