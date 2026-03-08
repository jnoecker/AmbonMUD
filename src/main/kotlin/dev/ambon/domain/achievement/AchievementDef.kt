package dev.ambon.domain.achievement

import dev.ambon.domain.Rewards

data class AchievementDef(
    val id: String,
    val displayName: String,
    val description: String,
    val category: String,
    val criteria: List<AchievementCriterion>,
    val rewards: AchievementRewards = AchievementRewards(),
    val hidden: Boolean = false,
)

data class AchievementCriterion(
    val type: String,
    /** Mob templateKey for kill, questId for quest_complete, empty for reach_level. */
    val targetId: String = "",
    /** Kill count, target level, or 1 for quest_complete. */
    val count: Int = 1,
    val description: String = "",
)

data class AchievementRewards(
    override val xp: Long = 0L,
    override val gold: Long = 0L,
    /** Title string made available to the player on unlock. Null means no title reward. */
    val title: String? = null,
) : Rewards
