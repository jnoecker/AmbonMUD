package dev.ambon.domain.world.data

/**
 * YAML shape of a mob's spawn condition. Parsed into
 * [dev.ambon.domain.world.SpawnCondition] by the world loader. All facets are
 * optional; an omitted facet means "any". Example:
 *
 * ```yaml
 * condition:
 *   time: [NIGHT]
 *   weather: [STORM]
 *   seasons: [WINTER]
 *   chance: 0.25
 * ```
 */
data class SpawnConditionFile(
    /** Times of day: DAWN, DAY, DUSK, NIGHT. */
    val time: List<String> = emptyList(),
    /** Weather type ids: CLEAR, RAIN, STORM, FOG, SNOW, WIND, … */
    val weather: List<String> = emptyList(),
    /** Seasons: SPRING, SUMMER, AUTUMN, WINTER. */
    val seasons: List<String> = emptyList(),
    /** World-event flags, any one of which activates the condition. */
    val events: List<String> = emptyList(),
    /** Per-opportunity appearance probability (0.0–1.0). Defaults to 1.0. */
    val chance: Double = 1.0,
)
