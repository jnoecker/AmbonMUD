package dev.ambon.domain.quest

import dev.ambon.domain.Progress

typealias ObjectiveProgress = Progress

data class QuestState(
    val questId: String,
    val acceptedAtEpochMs: Long,
    val objectives: List<ObjectiveProgress>,
)
