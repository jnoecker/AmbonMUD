package dev.ambon.engine

import dev.ambon.domain.achievement.AchievementDef

class AchievementRegistry {
    private val achievements = mutableMapOf<String, AchievementDef>()

    fun register(def: AchievementDef) {
        achievements[def.id] = def
    }

    fun get(id: String): AchievementDef? = achievements[id]

    fun all(): Collection<AchievementDef> = achievements.values
}
