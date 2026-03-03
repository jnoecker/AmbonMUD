package dev.ambon.domain.crafting

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId

data class GatheringYield(
    val itemId: ItemId,
    val minQuantity: Int = 1,
    val maxQuantity: Int = 1,
)

data class GatheringNodeDef(
    val id: String,
    val displayName: String,
    val keyword: String,
    val skill: CraftingSkill,
    val skillRequired: Int = 1,
    val yields: List<GatheringYield>,
    val respawnSeconds: Int = 60,
    val xpReward: Int = 10,
    val roomId: RoomId,
)
