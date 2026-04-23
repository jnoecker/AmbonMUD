package dev.ambon.domain.world.data

data class QuestFile(
    val name: String = "",
    val description: String = "",
    val giver: String = "",
    val completionType: String = "AUTO",
    val objectives: List<QuestObjectiveFile> = emptyList(),
    val rewards: QuestRewardsFile = QuestRewardsFile(),
    /** Optional reputation gate. min → giver hints to grind; max → quest disappears when exceeded. */
    val requiredReputation: ReputationRequirementFile? = null,
    /** Intended player level. Drives XP diminishing returns on completion when set. */
    val level: Int? = null,
    /**
     * Engine-driven difficulty tier. Omit or leave blank to use the authored
     * `rewards.xp` as-is; set `trivial|easy|standard|hard|epic` to let the
     * engine compute XP from quest level × tier multiplier.
     */
    val difficulty: String? = null,
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
    val currencies: Map<String, Long> = emptyMap(),
)
