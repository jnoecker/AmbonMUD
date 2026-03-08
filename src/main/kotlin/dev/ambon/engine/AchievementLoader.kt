package dev.ambon.engine

import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.achievement.AchievementCriterion
import dev.ambon.domain.achievement.AchievementDef
import dev.ambon.domain.achievement.AchievementRewards
import dev.ambon.persistence.yamlMapper

/** Flat DTO for YAML deserialization of achievements.yaml. */
internal data class AchievementsFile(
    val achievements: Map<String, AchievementEntryFile> = emptyMap(),
)

internal data class AchievementEntryFile(
    val displayName: String = "",
    val description: String = "",
    val category: String = "COMBAT",
    val hidden: Boolean = false,
    val criteria: List<AchievementCriterionFile> = emptyList(),
    val rewards: AchievementRewardsFile = AchievementRewardsFile(),
)

internal data class AchievementCriterionFile(
    val type: String = "",
    val targetId: String = "",
    val count: Int = 1,
    val description: String = "",
)

internal data class AchievementRewardsFile(
    val xp: Long = 0L,
    val gold: Long = 0L,
    val title: String? = null,
)

object AchievementLoader {
    /**
     * Loads achievements from a classpath resource and registers them in [registry].
     * Safe to call with a non-existent resource — will log a warning and skip.
     */
    fun loadFromResource(
        resourcePath: String,
        registry: AchievementRegistry,
        categoryRegistry: AchievementCategoryRegistry? = null,
    ) {
        val stream =
            AchievementLoader::class.java.classLoader.getResourceAsStream(resourcePath)
                ?: return

        val file = yamlMapper.readValue<AchievementsFile>(stream)
        for ((rawId, entry) in file.achievements) {
            val id = rawId.trim()
            require(id.isNotEmpty()) { "Achievement id cannot be blank" }
            require(entry.displayName.isNotBlank()) { "Achievement '$id' displayName cannot be blank" }
            require(entry.criteria.isNotEmpty()) { "Achievement '$id' must have at least one criterion" }

            val category = entry.category.trim().lowercase()
            require(category.isNotEmpty()) { "Achievement '$id' category cannot be blank" }
            if (categoryRegistry != null) {
                require(categoryRegistry.isValid(category)) {
                    "Achievement '$id' has unknown category '$category'"
                }
            }

            val criteria =
                entry.criteria.mapIndexed { index, cf ->
                    val type = cf.type.trim().lowercase()
                    require(type.isNotEmpty()) {
                        "Achievement '$id' criterion #${index + 1} type cannot be blank"
                    }
                    require(cf.count >= 1) {
                        "Achievement '$id' criterion #${index + 1} count must be >= 1"
                    }
                    AchievementCriterion(
                        type = type,
                        targetId = cf.targetId.trim(),
                        count = cf.count,
                        description = cf.description,
                    )
                }

            registry.register(
                AchievementDef(
                    id = id,
                    displayName = entry.displayName,
                    description = entry.description,
                    category = category,
                    criteria = criteria,
                    rewards =
                        AchievementRewards(
                            xp = entry.rewards.xp,
                            gold = entry.rewards.gold,
                            title = entry.rewards.title,
                        ),
                    hidden = entry.hidden,
                ),
            )
        }
    }
}
