package dev.ambon.engine

import dev.ambon.domain.achievement.AchievementDef

class AchievementRegistry : DefinitionRegistry<String, AchievementDef>({ it.id }) {
    /**
     * Bonus skill points granted by the achievements in [unlockedIds] (D-05 post-cap income).
     * Derived on demand from the definitions, like the prestige perk bonus, so it needs no
     * persisted counter and follows content edits.
     */
    fun skillPointBonus(unlockedIds: Collection<String>): Int = unlockedIds.sumOf { get(it)?.rewards?.skillPoints ?: 0 }
}
