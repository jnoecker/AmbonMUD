package dev.ambon.engine

import dev.ambon.config.WeatherConfig
import java.time.Clock

/**
 * Manages per-zone weather state with random transitions.
 *
 * Each zone independently transitions between weather types on a random
 * schedule within the configured interval range.
 */
class WeatherSystem(
    private val config: WeatherConfig,
    private val clock: Clock,
    private val rng: java.util.Random = java.util.Random(),
) {
    private val zoneWeather = mutableMapOf<String, WeatherType>()
    private val nextTransition = mutableMapOf<String, Long>()

    fun weatherForZone(zone: String): WeatherType =
        zoneWeather.getOrPut(zone) { WeatherType.CLEAR }

    fun allZoneWeather(): Map<String, WeatherType> = zoneWeather.toMap()

    /**
     * Called each engine tick. Returns a map of zones whose weather changed this tick.
     * Zones are lazily initialized on first [weatherForZone] call or when a player
     * enters a zone.
     */
    fun tick(activeZones: Set<String>): Map<String, WeatherType> {
        val now = clock.millis()
        val changes = mutableMapOf<String, WeatherType>()
        for (zone in activeZones) {
            zoneWeather.getOrPut(zone) { WeatherType.CLEAR }
            val deadline = nextTransition.getOrPut(zone) { scheduleNext(now) }
            if (now >= deadline) {
                val current = zoneWeather[zone] ?: WeatherType.CLEAR
                val next = rollNextWeather(current)
                zoneWeather[zone] = next
                nextTransition[zone] = scheduleNext(now)
                if (next != current) {
                    changes[zone] = next
                }
            }
        }
        return changes
    }

    /** Force-sets weather for a zone (e.g., for events or staff commands). */
    fun setWeather(zone: String, weather: WeatherType) {
        zoneWeather[zone] = weather
        nextTransition[zone] = scheduleNext(clock.millis())
    }

    private fun scheduleNext(now: Long): Long {
        val range = config.maxTransitionMs - config.minTransitionMs
        val delay = config.minTransitionMs + (rng.nextDouble() * range).toLong()
        return now + delay
    }

    private fun rollNextWeather(current: WeatherType): WeatherType {
        // Weighted transitions: favor clear/rain, less likely storm/snow
        val candidates = WeatherType.entries.filter { it != current }
        val weights = candidates.map { it.weight }
        val total = weights.sum()
        var roll = rng.nextDouble() * total
        for ((i, w) in weights.withIndex()) {
            roll -= w
            if (roll <= 0) return candidates[i]
        }
        return WeatherType.CLEAR
    }
}

enum class WeatherType(
    val displayName: String,
    val description: String,
    val weight: Double,
) {
    CLEAR("Clear", "The sky is clear.", 3.0),
    RAIN("Rain", "A steady rain falls.", 2.0),
    STORM("Storm", "Thunder rumbles and lightning splits the sky.", 0.5),
    FOG("Fog", "A thick fog blankets the area.", 1.0),
    SNOW("Snow", "Soft snow drifts down from above.", 0.8),
    WIND("Wind", "A fierce wind howls through the area.", 1.0),
}
