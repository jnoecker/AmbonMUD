package dev.ambon.domain.crafting

import dev.ambon.domain.ids.ItemId

data class MaterialRequirement(
    val itemId: ItemId,
    val quantity: Int = 1,
)

data class RecipeDef(
    val id: String,
    val displayName: String,
    val skill: String,
    val skillRequired: Int = 1,
    val levelRequired: Int = 1,
    val materials: List<MaterialRequirement>,
    val outputItemId: ItemId,
    val outputQuantity: Int = 1,
    val stationType: String? = null,
    val stationBonus: Int = 0,
    val xpReward: Int = 25,
)
