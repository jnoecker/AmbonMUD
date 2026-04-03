package dev.ambon.engine

import dev.ambon.config.PrestigeConfig
import dev.ambon.config.PrestigePerkConfig
import dev.ambon.domain.StatMap
import kotlin.math.pow

/**
 * Manages prestige ranks — horizontal endgame progression for max-level players.
 *
 * Players spend accumulated surplus XP (above what their current level requires) to
 * gain prestige ranks, each granting a permanent perk (stat bonus, skill points,
 * max HP/mana, or a title).
 */
class PrestigeSystem(
    private val config: PrestigeConfig,
    private val progression: PlayerProgression,
) {
    fun isEnabled(): Boolean = config.enabled

    /**
     * Returns the XP cost to advance from [currentRank] to the next prestige rank.
     * Formula: `xpCostBase * xpCostMultiplier ^ currentRank`.
     */
    fun xpCostForNextRank(currentRank: Int): Long =
        (config.xpCostBase * config.xpCostMultiplier.pow(currentRank)).toLong()

    /**
     * Returns the surplus XP a player has available for prestige after accounting for
     * the XP required to maintain their current level and any XP already spent on prestige.
     */
    fun availableXp(player: PlayerState): Long =
        player.xpTotal - player.prestigeXpSpent - progression.totalXpForLevel(player.level)

    /**
     * Checks whether the player meets all requirements to prestige:
     * enabled, max level, below max rank, and enough surplus XP.
     */
    fun canPrestige(player: PlayerState, maxLevel: Int): Boolean =
        config.enabled &&
            player.level >= maxLevel &&
            player.prestigeLevel < config.maxRank &&
            availableXp(player) >= xpCostForNextRank(player.prestigeLevel)

    /**
     * Attempts to advance the player's prestige rank by one.
     * Returns the result on success, or null if requirements are not met.
     * Mutates [player] in place (deducts XP, increments rank, applies perk).
     */
    fun prestige(player: PlayerState, maxLevel: Int): PrestigeResult? {
        if (!canPrestige(player, maxLevel)) return null
        val cost = xpCostForNextRank(player.prestigeLevel)
        player.prestigeXpSpent += cost
        player.prestigeLevel++
        val perk = config.perks[player.prestigeLevel]
        if (perk != null) {
            applyPerk(player, perk)
        }
        return PrestigeResult(player.prestigeLevel, cost, perk)
    }

    /** Applies the immediate effects of a perk to the player's runtime state. */
    private fun applyPerk(player: PlayerState, perk: PrestigePerkConfig) {
        when (perk.type.uppercase()) {
            "STAT_BONUS" -> applyStatBonus(player, perk)
            "SKILL_POINT" -> { /* Skill points are computed dynamically via accumulatedSkillPointBonus */ }
            "TITLE" -> { /* Titles are computed dynamically via unlockedTitles */ }
            "MAX_HP" -> {
                player.baseMaxHp += perk.amount
                player.maxHp += perk.amount
                player.hp += perk.amount
            }
            "MAX_MANA" -> {
                player.baseMana += perk.amount
                player.maxMana += perk.amount
                player.mana += perk.amount
            }
        }
    }

    private fun applyStatBonus(player: PlayerState, perk: PrestigePerkConfig) {
        val stat = perk.stat?.uppercase() ?: return
        val bonus = if (stat == "ALL") {
            StatMap.of(
                "STR" to perk.amount,
                "DEX" to perk.amount,
                "CON" to perk.amount,
                "INT" to perk.amount,
                "WIS" to perk.amount,
                "CHA" to perk.amount,
            )
        } else {
            StatMap.of(stat to perk.amount)
        }
        player.stats = player.stats + bonus
    }

    /** Accumulated stat bonuses from all prestige ranks up to [prestigeLevel]. */
    fun accumulatedStatBonuses(prestigeLevel: Int): Map<String, Int> {
        val bonuses = mutableMapOf<String, Int>()
        for (rank in 1..prestigeLevel) {
            val perk = config.perks[rank] ?: continue
            if (perk.type.uppercase() != "STAT_BONUS") continue
            val stat = perk.stat?.uppercase() ?: continue
            if (stat == "ALL") {
                for (s in listOf("STR", "DEX", "CON", "INT", "WIS", "CHA")) {
                    bonuses[s] = (bonuses[s] ?: 0) + perk.amount
                }
            } else {
                bonuses[stat] = (bonuses[stat] ?: 0) + perk.amount
            }
        }
        return bonuses
    }

    /** Accumulated max HP bonus from all prestige ranks up to [prestigeLevel]. */
    fun accumulatedMaxHpBonus(prestigeLevel: Int): Int =
        (1..prestigeLevel).sumOf { rank ->
            val perk = config.perks[rank] ?: return@sumOf 0
            if (perk.type.uppercase() == "MAX_HP") perk.amount else 0
        }

    /** Accumulated max mana bonus from all prestige ranks up to [prestigeLevel]. */
    fun accumulatedMaxManaBonus(prestigeLevel: Int): Int =
        (1..prestigeLevel).sumOf { rank ->
            val perk = config.perks[rank] ?: return@sumOf 0
            if (perk.type.uppercase() == "MAX_MANA") perk.amount else 0
        }

    /** Accumulated skill point bonus from all prestige ranks up to [prestigeLevel]. */
    fun accumulatedSkillPointBonus(prestigeLevel: Int): Int =
        (1..prestigeLevel).sumOf { rank ->
            val perk = config.perks[rank] ?: return@sumOf 0
            if (perk.type.uppercase() == "SKILL_POINT") perk.amount else 0
        }

    /** Titles unlocked through prestige ranks up to [prestigeLevel]. */
    fun unlockedTitles(prestigeLevel: Int): List<String> =
        (1..prestigeLevel).mapNotNull { rank ->
            val perk = config.perks[rank] ?: return@mapNotNull null
            if (perk.type.uppercase() == "TITLE") perk.title else null
        }

    /** Returns the perk definition for a given rank, or null. */
    fun perkForRank(rank: Int): PrestigePerkConfig? = config.perks[rank]

    /** The maximum prestige rank. */
    val maxRank: Int get() = config.maxRank
}

data class PrestigeResult(
    val newRank: Int,
    val xpSpent: Long,
    val perk: PrestigePerkConfig?,
)
