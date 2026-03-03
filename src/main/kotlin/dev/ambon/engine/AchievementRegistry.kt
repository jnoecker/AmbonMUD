package dev.ambon.engine

import dev.ambon.domain.achievement.AchievementDef

class AchievementRegistry : DefinitionRegistry<String, AchievementDef>({ it.id })
