package dev.ambon.engine

import dev.ambon.config.WeatherConfig
import dev.ambon.config.WeatherTypeDefinition
import java.time.Clock

/**
 * Manages per-zone weather state with random transitions.
 *
 * Each zone independently transitions between weather types on a random
 * schedule within the configured interval range.
 *
 * Weather types are fully config-driven via [WeatherConfig.types].
 */
class WeatherSystem(
    private val config: WeatherConfig,
    private val clock: Clock,
    private val rng: java.util.Random = java.util.Random(),
) {
    private val zoneWeather = mutableMapOf<String, String>()
    private val nextTransition = mutableMapOf<String, Long>()

    /** Returns the weather type ID for the given zone (defaults to first type, typically "CLEAR"). */
    fun weatherForZone(zone: String): String =
        zoneWeather.getOrPut(zone) { defaultWeatherType() }

    /** Returns the definition for a weather type ID, or null if unknown. */
    fun typeDefinition(typeId: String): WeatherTypeDefinition? = config.types[typeId]

    fun allZoneWeather(): Map<String, String> = zoneWeather.toMap()

    /**
     * Called each engine tick. Returns a map of zones whose weather changed this tick.
     * Zones are lazily initialized on first [weatherForZone] call or when a player
     * enters a zone.
     */
    fun tick(activeZones: Set<String>): Map<String, String> {
        val now = clock.millis()
        val changes = mutableMapOf<String, String>()
        for (zone in activeZones) {
            zoneWeather.getOrPut(zone) { defaultWeatherType() }
            val deadline = nextTransition.getOrPut(zone) { scheduleNext(now) }
            if (now >= deadline) {
                val current = zoneWeather[zone] ?: defaultWeatherType()
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
    fun setWeather(zone: String, weatherTypeId: String) {
        zoneWeather[zone] = weatherTypeId
        nextTransition[zone] = scheduleNext(clock.millis())
    }

    private fun defaultWeatherType(): String = config.types.keys.first()

    private fun scheduleNext(now: Long): Long {
        val range = config.maxTransitionMs - config.minTransitionMs
        val delay = config.minTransitionMs + (rng.nextDouble() * range).toLong()
        return now + delay
    }

    private fun rollNextWeather(current: String): String {
        val candidates = config.types.entries.filter { it.key != current }
        if (candidates.isEmpty()) return current
        val weights = candidates.map { it.value.weight }
        val total = weights.sum()
        var roll = rng.nextDouble() * total
        for ((i, w) in weights.withIndex()) {
            roll -= w
            if (roll <= 0) return candidates[i].key
        }
        return defaultWeatherType()
    }
}

/**
 * Legacy enum kept for backward compatibility with existing handler/test code.
 * New code should use the config-driven [WeatherTypeDefinition] via [WeatherConfig.types].
 */
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
