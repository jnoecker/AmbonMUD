package dev.ambon.domain.quest

import dev.ambon.domain.Rewards

data class QuestDef(
    val id: String,
    val name: String,
    val description: String,
    val giverMobId: String,
    val objectives: List<QuestObjectiveDef>,
    val rewards: QuestRewards,
    val completionType: String = "auto",
)

data class QuestObjectiveDef(
    val type: String,
    val targetId: String,
    val count: Int,
    val description: String,
)

data class QuestRewards(
    override val xp: Long = 0L,
    override val gold: Long = 0L,
) : Rewards
