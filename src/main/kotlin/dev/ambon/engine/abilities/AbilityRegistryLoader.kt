package dev.ambon.engine.abilities

import dev.ambon.config.AbilityEngineConfig
import dev.ambon.domain.DamageRange
import dev.ambon.engine.status.StatusEffectId

object AbilityRegistryLoader {
    fun load(
        config: AbilityEngineConfig,
        registry: AbilityRegistry,
        imagesBaseUrl: String = "/images/",
    ) {
        val imagesBase = if (imagesBaseUrl.endsWith("/")) imagesBaseUrl else "$imagesBaseUrl/"
        for ((key, defConfig) in config.definitions) {
            val targetType = defConfig.targetType.trim().lowercase()
            val effect =
                when (defConfig.effect.type.uppercase()) {
                    "DIRECT_DAMAGE" ->
                        AbilityEffect.DirectDamage(
                            damage = DamageRange(defConfig.effect.minDamage, defConfig.effect.maxDamage),
                            damagePerLevel = defConfig.effect.damagePerLevel,
                        )
                    "DIRECT_HEAL" ->
                        AbilityEffect.DirectHeal(
                            minHeal = defConfig.effect.minHeal,
                            maxHeal = defConfig.effect.maxHeal,
                            healPerLevel = defConfig.effect.healPerLevel,
                        )
                    "APPLY_STATUS" ->
                        AbilityEffect.ApplyStatus(
                            statusEffectId = StatusEffectId(defConfig.effect.statusEffectId),
                        )
                    "AREA_DAMAGE" ->
                        AbilityEffect.AreaDamage(
                            damage = DamageRange(defConfig.effect.minDamage, defConfig.effect.maxDamage),
                            damagePerLevel = defConfig.effect.damagePerLevel,
                        )
                    "TAUNT" ->
                        AbilityEffect.Taunt(
                            flatThreat = defConfig.effect.flatThreat,
                            margin = defConfig.effect.margin,
                        )
                    "SUMMON_PET" ->
                        AbilityEffect.SummonPet(
                            petTemplateKey = defConfig.effect.petTemplateKey,
                            durationMs = defConfig.effect.durationMs,
                        )
                    else -> continue
                }
            val requiredClass = defConfig.requiredClass.ifBlank { null }
            val prerequisites = defConfig.prerequisites.map { AbilityId(it) }.toSet()
            val tree = defConfig.tree.ifBlank { "" }
            registry.register(
                AbilityDefinition(
                    id = AbilityId(key),
                    displayName = defConfig.displayName.ifEmpty { key },
                    description = defConfig.description,
                    manaCost = defConfig.manaCost,
                    cooldownMs = defConfig.cooldownMs,
                    levelRequired = defConfig.levelRequired,
                    skillPointCost = defConfig.skillPointCost,
                    targetType = targetType,
                    effect = effect,
                    requiredClass = requiredClass,
                    image = defConfig.image.ifBlank { null }?.let { "$imagesBase$it" },
                    prerequisites = prerequisites,
                    tree = tree,
                    tier = defConfig.tier,
                ),
            )
        }
        validateNoPrerequisiteCycles(registry)
    }

    /**
     * Validates that the prerequisite graph is a DAG (no circular chains).
     * Throws [IllegalStateException] if a cycle is detected.
     */
    internal fun validateNoPrerequisiteCycles(registry: AbilityRegistry) {
        val allAbilities = registry.all().associateBy { it.id }

        // Standard DFS-based cycle detection
        val visited = mutableSetOf<AbilityId>()
        val inStack = mutableSetOf<AbilityId>()

        fun dfs(id: AbilityId, path: MutableList<AbilityId>) {
            if (id in inStack) {
                val cycleStart = path.indexOf(id)
                val cycle = path.subList(cycleStart, path.size) + id
                val names = cycle.map { aid ->
                    allAbilities[aid]?.displayName ?: aid.value
                }
                error(
                    "Circular prerequisite chain detected: ${names.joinToString(" -> ")}",
                )
            }
            if (id in visited) return
            val ability = allAbilities[id] ?: return

            inStack.add(id)
            path.add(id)
            for (prereq in ability.prerequisites) {
                dfs(prereq, path)
            }
            path.removeAt(path.lastIndex)
            inStack.remove(id)
            visited.add(id)
        }

        for (id in allAbilities.keys) {
            if (id !in visited) {
                dfs(id, mutableListOf())
            }
        }
    }
}
