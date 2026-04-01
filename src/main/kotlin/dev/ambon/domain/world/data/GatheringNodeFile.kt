package dev.ambon.domain.world.data

data class GatheringYieldFile(
    val itemId: String,
    val minQuantity: Int = 1,
    val maxQuantity: Int = 1,
)

data class RareGatheringYieldFile(
    val itemId: String,
    val quantity: Int = 1,
    val dropChance: Double = 0.1,
)

data class GatheringNodeFile(
    val displayName: String,
    val keyword: String? = null,
    val image: String? = null,
    val skill: String,
    val skillRequired: Int = 1,
    val yields: List<GatheringYieldFile> = emptyList(),
    val rareYields: List<RareGatheringYieldFile> = emptyList(),
    val respawnSeconds: Int = 60,
    val xpReward: Int = 10,
    val room: String,
)
