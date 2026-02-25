package dev.ambon.domain.achievement

/**
 * In-progress tracking for a single achievement.
 * One [CriterionProgress] per [AchievementCriterion] in [AchievementDef.criteria],
 * indexed in parallel (mirrors the QuestState / ObjectiveProgress pattern).
 */
data class AchievementState(
    val achievementId: String,
    val progress: List<CriterionProgress>,
)

data class CriterionProgress(
    val current: Int,
    val required: Int,
) {
    val isComplete: Boolean get() = current >= required
}
