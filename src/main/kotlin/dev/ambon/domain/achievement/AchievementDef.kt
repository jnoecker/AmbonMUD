package dev.ambon.domain.achievement

data class AchievementDef(
    val id: String,
    val displayName: String,
    val description: String,
    val category: AchievementCategory,
    val criteria: List<AchievementCriterion>,
    val rewards: AchievementRewards = AchievementRewards(),
    val hidden: Boolean = false,
)

data class AchievementCriterion(
    val type: CriterionType,
    /** Mob templateKey for KILL, questId for QUEST_COMPLETE, empty for REACH_LEVEL. */
    val targetId: String = "",
    /** Kill count, target level, or 1 for QUEST_COMPLETE. */
    val count: Int = 1,
    val description: String = "",
)

data class AchievementRewards(
    val xp: Long = 0L,
    val gold: Long = 0L,
    /** Title string made available to the player on unlock. Null means no title reward. */
    val title: String? = null,
)

enum class AchievementCategory { COMBAT, EXPLORATION, SOCIAL, CRAFTING, CLASS }

enum class CriterionType { KILL, REACH_LEVEL, QUEST_COMPLETE }
