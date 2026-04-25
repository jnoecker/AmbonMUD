package dev.ambon.domain.world.data

data class QuestFile(
    val name: String = "",
    val description: String = "",
    val giver: String = "",
    val completionType: String = "NPC_TURN_IN",
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
    /**
     * Optional dialogue-flag gate. When set, the quest is hidden from the
     * Quest button and `qoffers` until the player has the named flag in
     * their dialogueFlags set. Flags are added by dialogue choice actions
     * of the form `unlock_flag:<name>`.
     */
    val requiresDialogueFlag: String? = null,
    /**
     * Optional override for the NPC that accepts turn-ins. Bare mob keyword
     * (e.g. `headmaster_aldric`); the loader qualifies it with the zone id.
     * Defaults to [giver] when null.
     */
    val turnInMob: String? = null,
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
