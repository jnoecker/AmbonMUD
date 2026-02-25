package dev.ambon.domain.quest

data class QuestDef(
    val id: String,
    val name: String,
    val description: String,
    val giverMobId: String,
    val objectives: List<QuestObjectiveDef>,
    val rewards: QuestRewards,
    val completionType: CompletionType = CompletionType.AUTO,
)

data class QuestObjectiveDef(
    val type: ObjectiveType,
    val targetId: String,
    val count: Int,
    val description: String,
)

data class QuestRewards(
    val xp: Long = 0L,
    val gold: Long = 0L,
)

enum class ObjectiveType { KILL, COLLECT }

enum class CompletionType { AUTO, NPC_TURN_IN }
