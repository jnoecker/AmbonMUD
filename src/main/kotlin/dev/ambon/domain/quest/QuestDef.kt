package dev.ambon.domain.quest

import dev.ambon.config.QuestDifficulty
import dev.ambon.domain.Rewards
import dev.ambon.domain.world.ReputationRequirement

data class QuestDef(
    val id: String,
    val name: String,
    val description: String,
    val giverMobId: String,
    val objectives: List<QuestObjectiveDef>,
    val rewards: QuestRewards,
    val completionType: String = "npc_turn_in",
    /** Optional reputation gate. */
    val requiredReputation: ReputationRequirement? = null,
    /**
     * Intended player level for this quest. When set, XP rewards are scaled by
     * the same diminishing-returns curve used for kills — a player who has
     * out-levelled the quest receives reduced XP instead of the flat reward.
     * Null preserves legacy flat-award behaviour.
     */
    val level: Int? = null,
    /**
     * Engine-driven difficulty tier. When non-null and `rewards.xp == 0`, the
     * engine computes XP from the progression config's quest baseline and the
     * tier's multiplier. An explicit positive `rewards.xp` always wins as an
     * override.
     */
    val difficulty: QuestDifficulty? = null,
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
    val currencies: Map<String, Long> = emptyMap(),
) : Rewards
