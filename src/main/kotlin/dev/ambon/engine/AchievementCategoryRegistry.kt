package dev.ambon.engine

import dev.ambon.config.AchievementCategoriesConfig

class AchievementCategoryRegistry(
    config: AchievementCategoriesConfig,
) {
    private val categories: Map<String, String> =
        config.categories.mapValues { (key, cfg) ->
            cfg.displayName.ifBlank { key.replaceFirstChar { it.uppercase() } }
        }

    fun displayName(categoryId: String): String =
        categories[categoryId] ?: categoryId.replaceFirstChar { it.uppercase() }

    fun isValid(categoryId: String): Boolean = categoryId in categories

    fun allCategoryIds(): List<String> = categories.keys.toList()
}
