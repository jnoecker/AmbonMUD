package dev.ambon.engine.abilities

import dev.ambon.config.AbilityEffectConfig
import dev.ambon.config.AbilityEngineConfig
import dev.ambon.config.AbilityVisualConfig
import dev.ambon.domain.DamageRange
import dev.ambon.engine.status.StatusEffectId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

object AbilityRegistryLoader {
    fun load(
        config: AbilityEngineConfig,
        registry: AbilityRegistry,
        imagesBaseUrl: String = "/images/",
    ) {
        val imagesBase = if (imagesBaseUrl.endsWith("/")) imagesBaseUrl else "$imagesBaseUrl/"
        for ((key, defConfig) in config.definitions) {
            val targetType = defConfig.targetType.trim().lowercase()
            val effect = parseEffect(defConfig.effect)
            if (effect == null) {
                logger.warn {
                    "Ability '$key' skipped: effect type '${defConfig.effect.type}' is unknown " +
                        "or has unparseable children."
                }
                continue
            }
            val requiredClass = defConfig.requiredClass.ifBlank { null }
            val prerequisites = defConfig.prerequisites.map { AbilityId(it) }.toSet()
            val tree = defConfig.tree.ifBlank { "" }
            val resolvedImage = defConfig.image.ifBlank { null }?.let { "$imagesBase$it" }
            val visual = resolveVisual(defConfig.visual, effect, targetType, requiredClass, imagesBase)
            registry.register(
                AbilityDefinition(
                    id = AbilityId(key),
                    displayName = defConfig.displayName.ifEmpty { key },
                    description = defConfig.description,
                    manaCostPct = defConfig.manaCostPct,
                    cooldownMs = defConfig.cooldownMs,
                    levelRequired = defConfig.levelRequired,
                    skillPointCost = defConfig.skillPointCost,
                    targetType = targetType,
                    effect = effect,
                    requiredClass = requiredClass,
                    image = resolvedImage,
                    prerequisites = prerequisites,
                    tree = tree,
                    tier = defConfig.tier,
                    visual = visual,
                ),
            )
        }
        validateNoPrerequisiteCycles(registry)
    }

    /**
     * Parses a single [AbilityEffectConfig] into an [AbilityEffect], recursing
     * for composite effects. Returns null for unknown effect types. For
     * composites, returns null if any declared child fails to parse — partial
     * composites are not allowed because they would silently change the
     * intended combat behavior (e.g. a typo'd child silently dropped from a
     * damage+status spell). Composites with an empty `effects` list are also
     * rejected. The caller surfaces a warning and skips the whole ability.
     */
    private fun parseEffect(config: AbilityEffectConfig): AbilityEffect? =
        when (config.type.uppercase()) {
            "DIRECT_DAMAGE" -> AbilityEffect.DirectDamage(
                damage = DamageRange(config.minDamage, config.maxDamage),
            )
            "DIRECT_HEAL" -> AbilityEffect.DirectHeal(
                minHeal = config.minHeal,
                maxHeal = config.maxHeal,
            )
            "APPLY_STATUS" -> AbilityEffect.ApplyStatus(
                statusEffectId = StatusEffectId(config.statusEffectId),
            )
            "AREA_DAMAGE" -> AbilityEffect.AreaDamage(
                damage = DamageRange(config.minDamage, config.maxDamage),
            )
            "TAUNT" -> AbilityEffect.Taunt(
                flatThreat = config.flatThreat,
                margin = config.margin,
            )
            "SUMMON_PET" -> AbilityEffect.SummonPet(
                petTemplateKey = config.petTemplateKey,
                durationMs = config.durationMs,
            )
            "COMPOSITE" -> {
                if (config.effects.isEmpty()) {
                    logger.warn {
                        "COMPOSITE effect has no children — composites must declare at least one child effect."
                    }
                    null
                } else {
                    val children = config.effects.map { child ->
                        val parsed = parseEffect(child)
                        if (parsed == null) {
                            logger.warn {
                                "COMPOSITE child of type '${child.type}' failed to parse — " +
                                    "the entire composite will be rejected to avoid partial behavior."
                            }
                        }
                        parsed
                    }
                    if (children.any { it == null }) null else AbilityEffect.Composite(effects = children.filterNotNull())
                }
            }
            else -> null
        }

    /**
     * Resolves the [AbilityVisual] for an ability. An empty [AbilityVisualConfig.archetype]
     * delegates to [deriveDefaultVisual]; any non-blank field overrides the derived value.
     * The `projectileImage` is resolved against [imagesBase] just like the spellbook icon.
     */
    private fun resolveVisual(
        config: AbilityVisualConfig,
        effect: AbilityEffect,
        targetType: String,
        requiredClass: String?,
        imagesBase: String,
    ): AbilityVisual {
        val archetype = config.archetype.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { AbilityVisualArchetype.valueOf(it.uppercase()) }.getOrNull() }
            ?: deriveDefaultVisual(effect, targetType, requiredClass).archetype
        return AbilityVisual(
            archetype = archetype,
            projectileImage = config.projectileImage.ifBlank { null }?.let { "$imagesBase$it" },
            color = config.color.ifBlank { null },
            accentColor = config.accentColor.ifBlank { null },
        )
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
