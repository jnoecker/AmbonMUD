package dev.ambon.domain.world.data

data class GatheringYieldFile(
    val itemId: String,
    val minQuantity: Int = 1,
    val maxQuantity: Int = 1,
)

data class GatheringNodeFile(
    val displayName: String,
    val keyword: String? = null,
    val skill: String,
    val skillRequired: Int = 1,
    val yields: List<GatheringYieldFile> = emptyList(),
    val respawnSeconds: Int = 60,
    val xpReward: Int = 10,
    val room: String,
)
