package dev.ambon.domain.crafting

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId

data class GatheringYield(
    val itemId: ItemId,
    val minQuantity: Int = 1,
    val maxQuantity: Int = 1,
)

/** A bonus drop with a percentage chance (0.0–1.0). */
data class RareGatheringYield(
    val itemId: ItemId,
    val quantity: Int = 1,
    val dropChance: Double = 0.1,
)

data class GatheringNodeDef(
    val id: String,
    val displayName: String,
    val keyword: String,
    val image: String? = null,
    val skill: String,
    val skillRequired: Int = 1,
    val yields: List<GatheringYield>,
    val rareYields: List<RareGatheringYield> = emptyList(),
    val respawnSeconds: Int = 60,
    val xpReward: Int = 10,
    val roomId: RoomId,
)
