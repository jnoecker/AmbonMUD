package dev.ambon.engine.crafting

import dev.ambon.domain.crafting.RecipeDef
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

class CraftingRegistry {
    private val recipesById = mutableMapOf<String, RecipeDef>()

    fun register(recipes: List<RecipeDef>) {
        for (recipe in recipes) {
            recipesById[recipe.id] = recipe
        }
        if (recipes.isNotEmpty()) {
            log.info { "Registered ${recipes.size} crafting recipe(s)" }
        }
    }

    fun recipeById(id: String): RecipeDef? = recipesById[id]

    fun findRecipe(keyword: String): RecipeDef? {
        val lower = keyword.lowercase()
        return recipesById.values.firstOrNull { it.id.substringAfter(':').lowercase() == lower }
            ?: recipesById.values.firstOrNull { it.displayName.lowercase() == lower }
            ?: recipesById.values.firstOrNull { it.id.substringAfter(':').lowercase().startsWith(lower) }
            ?: recipesById.values.firstOrNull { it.displayName.lowercase().contains(lower) }
    }

    fun allRecipes(): Collection<RecipeDef> = recipesById.values

    fun recipesForSkill(skill: String): List<RecipeDef> =
        recipesById.values.filter { it.skill == skill }

    fun clear() {
        recipesById.clear()
    }
}
