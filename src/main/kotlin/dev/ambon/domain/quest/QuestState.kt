package dev.ambon.domain.quest

data class QuestState(
    val questId: String,
    val acceptedAtEpochMs: Long,
    val objectives: List<ObjectiveProgress>,
)

data class ObjectiveProgress(
    val current: Int,
    val required: Int,
) {
    val isComplete: Boolean get() = current >= required
}
