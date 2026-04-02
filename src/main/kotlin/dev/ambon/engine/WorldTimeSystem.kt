package dev.ambon.engine

import dev.ambon.config.WorldTimeConfig
import java.time.Clock

/**
 * Tracks an accelerated game-world day/night cycle.
 *
 * One full game day (24 game-hours) maps to [WorldTimeConfig.cycleLengthMs] of real time.
 * The current time period (DAWN, DAY, DUSK, NIGHT) is derived from the game hour.
 */
class WorldTimeSystem(
    private val config: WorldTimeConfig,
    private val clock: Clock,
) {
    private val startMs: Long = clock.millis()

    /** Returns the current game hour (0-23). */
    fun gameHour(): Int {
        val elapsed = clock.millis() - startMs
        val dayProgress = (elapsed % config.cycleLengthMs).toDouble() / config.cycleLengthMs
        return (dayProgress * 24).toInt().coerceIn(0, 23)
    }

    /** Returns the current game minute (0-59). */
    fun gameMinute(): Int {
        val elapsed = clock.millis() - startMs
        val dayProgress = (elapsed % config.cycleLengthMs).toDouble() / config.cycleLengthMs
        val totalMinutes = dayProgress * 24 * 60
        return (totalMinutes.toInt() % 60).coerceIn(0, 59)
    }

    /** Returns the current time period based on game hour. */
    fun period(): TimePeriod {
        val hour = gameHour()
        return when {
            hour >= config.nightHour -> TimePeriod.NIGHT
            hour >= config.duskHour -> TimePeriod.DUSK
            hour >= config.dayHour -> TimePeriod.DAY
            hour >= config.dawnHour -> TimePeriod.DAWN
            else -> TimePeriod.NIGHT
        }
    }

    /**
     * Called each engine tick. Returns the new [TimePeriod] if it changed since [lastPeriod],
     * or null if unchanged.
     */
    fun tick(lastPeriod: TimePeriod): TimePeriod? {
        val current = period()
        return if (current != lastPeriod) current else null
    }
}

enum class TimePeriod(
    val displayName: String,
    val description: String,
) {
    DAWN("Dawn", "The sky brightens with the first light of dawn."),
    DAY("Day", "Sunlight fills the world."),
    DUSK("Dusk", "Long shadows stretch as the sun sinks low."),
    NIGHT("Night", "Stars glitter overhead in the dark sky."),
}
