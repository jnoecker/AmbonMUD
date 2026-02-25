package dev.ambon.domain.world.data

data class QuestFile(
    val name: String = "",
    val description: String = "",
    val giver: String = "",
    val completionType: String = "AUTO",
    val objectives: List<QuestObjectiveFile> = emptyList(),
    val rewards: QuestRewardsFile = QuestRewardsFile(),
)

data class QuestObjectiveFile(
    val type: String = "",
    val targetKey: String = "",
    val count: Int = 1,
    val description: String = "",
)

data class QuestRewardsFile(
    val xp: Long = 0L,
    val gold: Long = 0L,
)
