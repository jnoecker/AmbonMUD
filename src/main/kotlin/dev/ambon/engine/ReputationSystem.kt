package dev.ambon.engine

import dev.ambon.config.FactionConfig
import dev.ambon.config.FactionDefinition
import dev.ambon.config.ReputationTier
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Manages per-player faction standings and reputation changes.
 *
 * Tier bands and default thresholds come from [FactionConfig.resolvedTiers].
 * Changes occur on mob kills (based on mob's faction) and quest completion
 * (based on quest reward config).
 */
class ReputationSystem(
    private val config: FactionConfig,
) {
    private val tiers: List<ReputationTier> = config.resolvedTiers()
    private val repFloor: Int = tiers.first().minReputation
    private val repCeiling: Int = tiers.last().minReputation

    fun factionDefinitions(): Map<String, FactionDefinition> = config.definitions

    fun getFaction(id: String): FactionDefinition? = config.definitions[id]

    /** Resolves the reputation tier for a raw reputation value. */
    fun tierFor(reputation: Int): ReputationTier =
        tiers.lastOrNull { it.minReputation <= reputation } ?: tiers.first()

    /** Tier display label for a raw reputation value. */
    fun tierLabel(reputation: Int): String = tierFor(reputation).label

    /** Returns the player's standing with a faction (default 0). */
    fun getStanding(player: PlayerState, factionId: String): Int =
        player.factionStandings.getOrDefault(factionId, config.defaultReputation)

    /** Returns all non-zero standings for a player. */
    fun allStandings(player: PlayerState): Map<String, Int> =
        config.definitions.keys.associateWith { getStanding(player, it) }

    /**
     * Adjusts a player's standing with a faction.
     * Returns the new standing value, or null if the faction doesn't exist.
     */
    fun adjustStanding(
        player: PlayerState,
        factionId: String,
        amount: Int,
    ): Int? {
        if (factionId !in config.definitions) return null
        val current = player.factionStandings.getOrDefault(factionId, config.defaultReputation)
        val newValue = (current + amount).coerceIn(repFloor, repCeiling)
        player.factionStandings[factionId] = newValue
        if (amount != 0) {
            log.debug { "Reputation change for ${player.name}: $factionId ${if (amount > 0) "+" else ""}$amount → $newValue" }
        }
        return newValue
    }

    /**
     * Processes reputation changes for a mob kill.
     * Mobs with a faction field grant reputation for killing them
     * (positive for enemies of that faction, negative for allies).
     */
    fun onMobKilled(
        player: PlayerState,
        mobFaction: String?,
        mobLevel: Int,
    ): List<ReputationChange> {
        if (mobFaction == null || mobFaction !in config.definitions) return emptyList()

        val changes = mutableListOf<ReputationChange>()

        // Killing a mob of faction X: lose rep with X, gain rep with X's enemies
        val killPenalty = config.killPenalty.coerceAtLeast(1) * (1 + mobLevel / 10)
        val newStanding = adjustStanding(player, mobFaction, -killPenalty)
        if (newStanding != null) {
            changes.add(ReputationChange(mobFaction, -killPenalty, newStanding))
        }

        // Grant rep to enemy factions (if configured)
        val enemies = config.definitions[mobFaction]?.enemies ?: emptyList()
        for (enemyFaction in enemies) {
            val bonus = config.killBonus.coerceAtLeast(1) * (1 + mobLevel / 10)
            val newEnemyStanding = adjustStanding(player, enemyFaction, bonus)
            if (newEnemyStanding != null) {
                changes.add(ReputationChange(enemyFaction, bonus, newEnemyStanding))
            }
        }

        return changes
    }

    /**
     * Processes reputation changes for quest completion.
     * Quest rewards are configured in the faction config.
     */
    fun onQuestCompleted(
        player: PlayerState,
        questId: String,
    ): List<ReputationChange> {
        val rewards = config.questRewards[questId] ?: return emptyList()
        val changes = mutableListOf<ReputationChange>()
        for ((factionId, amount) in rewards) {
            val newStanding = adjustStanding(player, factionId, amount)
            if (newStanding != null) {
                changes.add(ReputationChange(factionId, amount, newStanding))
            }
        }
        return changes
    }

    /** Checks if a player meets a faction requirement. */
    fun meetsRequirement(
        player: PlayerState,
        factionId: String,
        minReputation: Int,
    ): Boolean = getStanding(player, factionId) >= minReputation
}

data class ReputationChange(
    val factionId: String,
    val amount: Int,
    val newStanding: Int,
)
