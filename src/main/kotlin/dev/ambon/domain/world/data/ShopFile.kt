package dev.ambon.domain.world.data

data class ShopFile(
    val name: String,
    val room: String,
    val items: List<String> = emptyList(),
    val image: String? = null,
    /** Optional reputation gate on browse/buy. */
    val requiredReputation: ReputationRequirementFile? = null,
)
