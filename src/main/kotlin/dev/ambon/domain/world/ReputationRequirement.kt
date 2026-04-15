package dev.ambon.domain.world

/**
 * Runtime reputation gate on shops/quests. [min] and [max] are both optional;
 * if set, the player's reputation with [faction] must fall in `[min, max]`.
 */
data class ReputationRequirement(
    val faction: String,
    val min: Int? = null,
    val max: Int? = null,
)
