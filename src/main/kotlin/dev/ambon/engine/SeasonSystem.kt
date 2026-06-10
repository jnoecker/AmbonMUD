package dev.ambon.engine

import dev.ambon.config.SeasonConfig
import java.time.Clock

/**
 * Tracks an accelerated game-world seasonal cycle.
 *
 * One full game year (4 seasons) maps to [SeasonConfig.cycleLengthMs] of real
 * time, so each season lasts a quarter of that. The cycle order is
 * SPRING → SUMMER → AUTUMN → WINTER, mirroring [WorldTimeSystem]'s day/night clock.
 *
 * Content authors gate mob spawns on the current [Season] via spawn conditions;
 * see [dev.ambon.domain.world.SpawnCondition].
 */
class SeasonSystem(
    private val config: SeasonConfig,
    private val clock: Clock,
) {
    private val startMs: Long = clock.millis()

    /** Returns the current [Season] derived from elapsed real time. */
    fun season(): Season {
        val elapsed = clock.millis() - startMs
        val yearProgress = (elapsed % config.cycleLengthMs).toDouble() / config.cycleLengthMs
        val index = (yearProgress * Season.entries.size).toInt().coerceIn(0, Season.entries.size - 1)
        return Season.entries[index]
    }

    /**
     * Called each engine tick. Returns the new [Season] if it changed since
     * [lastSeason], or null if unchanged.
     */
    fun tick(lastSeason: Season): Season? {
        val current = season()
        return if (current != lastSeason) current else null
    }
}

enum class Season(
    val displayName: String,
    val description: String,
) {
    SPRING("Spring", "Tender green unfurls as the world wakes from its slumber."),
    SUMMER("Summer", "The land basks under a long, golden sun."),
    AUTUMN("Autumn", "Leaves turn to fire and the air carries a crisp chill."),
    WINTER("Winter", "Frost grips the world and snow muffles every footfall."),
}
