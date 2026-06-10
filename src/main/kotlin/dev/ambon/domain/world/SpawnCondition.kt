package dev.ambon.domain.world

import dev.ambon.engine.Season
import dev.ambon.engine.TimePeriod

/**
 * An authored gate controlling when a mob is present in the world. A mob with a
 * non-trivial condition has its entire spawn lifecycle owned by
 * [dev.ambon.engine.ConditionalSpawnHandler]: it appears when every specified
 * facet is satisfied (and a per-opportunity [chance] roll succeeds) and fades
 * out — never mid-combat — once any facet stops holding.
 *
 * Each facet is independent: an empty set means "any". Specified facets are
 * AND-ed together (time AND weather AND season AND an event flag), so
 * `time=[NIGHT], weather=[STORM]` means "only at night during a storm".
 */
data class SpawnCondition(
    /** Times of day during which the mob may appear. Empty = any time. */
    val timePeriods: Set<TimePeriod> = emptySet(),
    /** Weather type ids (e.g. "STORM", "SNOW") during which the mob may appear. Empty = any. */
    val weather: Set<String> = emptySet(),
    /** Seasons during which the mob may appear. Empty = any season. */
    val seasons: Set<Season> = emptySet(),
    /** World-event flags, any one of which must be active. Empty = no event requirement. */
    val eventFlags: Set<String> = emptySet(),
    /**
     * Probability the mob actually appears on each spawn opportunity (a periodic
     * check while the other gates hold). 1.0 = appears as soon as the gates do;
     * lower values make it a rare, intermittent sighting.
     */
    val chance: Double = 1.0,
) {
    /** True when this condition gates nothing — i.e. the mob spawns like any ordinary mob. */
    val isUnconditional: Boolean
        get() = timePeriods.isEmpty() &&
            weather.isEmpty() &&
            seasons.isEmpty() &&
            eventFlags.isEmpty() &&
            chance >= 1.0

    /**
     * Whether the continuous gates (everything except the random [chance]) are
     * currently satisfied. [chance] is rolled separately, per spawn opportunity.
     */
    fun gatesSatisfied(
        period: TimePeriod,
        weatherId: String,
        season: Season,
        activeEventFlags: Set<String>,
    ): Boolean {
        if (timePeriods.isNotEmpty() && period !in timePeriods) return false
        if (weather.isNotEmpty() && weatherId !in weather) return false
        if (seasons.isNotEmpty() && season !in seasons) return false
        if (eventFlags.isNotEmpty() && eventFlags.none { it in activeEventFlags }) return false
        return true
    }
}
