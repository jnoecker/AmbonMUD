package dev.ambon.domain.world.data

data class MaterialRequirementFile(
    val itemId: String,
    val quantity: Int = 1,
)

data class RecipeFile(
    val displayName: String,
    val skill: String,
    val skillRequired: Int = 1,
    val levelRequired: Int = 1,
    val materials: List<MaterialRequirementFile> = emptyList(),
    val outputItemId: String,
    val outputQuantity: Int = 1,
    val station: String? = null,
    val stationBonus: Int = 0,
    val xpReward: Int = 25,
)
