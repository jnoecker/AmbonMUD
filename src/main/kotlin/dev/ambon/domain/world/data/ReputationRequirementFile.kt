package dev.ambon.domain.world.data

/**
 * Reputation gate on a shop or quest. [min] and [max] are both optional;
 * when set, the player's standing with [faction] must fall in the inclusive
 * `[min, max]` range.
 */
data class ReputationRequirementFile(
    val faction: String = "",
    val min: Int? = null,
    val max: Int? = null,
)
