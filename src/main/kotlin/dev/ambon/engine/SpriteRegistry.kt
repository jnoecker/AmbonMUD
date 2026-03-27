package dev.ambon.engine

import dev.ambon.domain.sprite.SpriteCategory
import dev.ambon.domain.sprite.SpriteDefinition
import dev.ambon.domain.sprite.SpriteUnlockCondition
import dev.ambon.domain.sprite.SpriteVariant

/**
 * Registry of all known sprite definitions, plus resolution logic for
 * determining which sprites a player has unlocked and which variant to display.
 */
class SpriteRegistry {
    private val definitions = mutableMapOf<String, SpriteDefinition>()
    private val variantIndex = mutableMapOf<String, Pair<SpriteDefinition, SpriteVariant>>()

    fun register(def: SpriteDefinition) {
        definitions[def.id] = def
        for (v in def.variants) {
            variantIndex[v.imageId] = def to v
        }
    }

    fun get(id: String): SpriteDefinition? = definitions[id]

    fun all(): Collection<SpriteDefinition> = definitions.values

    /** Looks up a variant by its [imageId] and returns the owning definition + variant. */
    fun findVariant(imageId: String): Pair<SpriteDefinition, SpriteVariant>? = variantIndex[imageId]

    fun clear() {
        definitions.clear()
        variantIndex.clear()
    }

    // ------------------------------------------------------------------
    // Unlock & filtering helpers
    // ------------------------------------------------------------------

    /** Returns all definitions the player currently qualifies for. */
    fun unlockedDefinitions(
        level: Int,
        unlockedAchievementIds: Set<String>,
        isStaff: Boolean,
    ): List<SpriteDefinition> = definitions.values.filter { isUnlocked(it, level, unlockedAchievementIds, isStaff) }

    /** Returns all variants the player can see (unlocked + matches race/class/gender). */
    fun availableVariants(
        level: Int,
        unlockedAchievementIds: Set<String>,
        isStaff: Boolean,
        playerRace: String,
        playerClass: String,
        playerGender: String,
    ): List<Pair<SpriteDefinition, SpriteVariant>> {
        val result = mutableListOf<Pair<SpriteDefinition, SpriteVariant>>()
        for (def in definitions.values) {
            if (!isUnlocked(def, level, unlockedAchievementIds, isStaff)) continue
            for (v in def.variants) {
                if (def.category == SpriteCategory.STAFF || v.matchesPlayer(playerRace, playerClass, playerGender)) {
                    result.add(def to v)
                }
            }
        }
        return result
    }

    /**
     * Validates that [imageId] is an unlocked, player-matching variant.
     * Returns the resolved image path or `null` if invalid.
     */
    fun validateSelection(
        imageId: String,
        level: Int,
        unlockedAchievementIds: Set<String>,
        isStaff: Boolean,
        playerRace: String,
        playerClass: String,
        playerGender: String,
    ): SpriteVariant? {
        val (def, variant) = variantIndex[imageId] ?: return null
        if (!isUnlocked(def, level, unlockedAchievementIds, isStaff)) return null
        if (def.category != SpriteCategory.STAFF && !variant.matchesPlayer(playerRace, playerClass, playerGender)) return null
        return variant
    }

    /**
     * Auto-resolve: picks the highest-tier sprite with the best variant match.
     * Used when `activeSprite` is null (default/auto mode).
     */
    fun autoResolve(
        level: Int,
        isStaff: Boolean,
        playerRace: String,
        playerClass: String,
        playerGender: String,
    ): SpriteVariant? {
        // For auto, only consider tier sprites (or staff if isStaff)
        val candidates = definitions.values
            .filter { def ->
                when (def.category) {
                    SpriteCategory.TIER -> isUnlocked(def, level, emptySet(), isStaff = false)
                    SpriteCategory.STAFF -> isStaff
                    else -> false
                }
            }
            .sortedByDescending { it.sortOrder }

        for (def in candidates) {
            val best = bestVariant(def, playerRace, playerClass, playerGender)
            if (best != null) return best
        }
        return null
    }

    /**
     * Picks the most specific matching variant from a definition for the given player.
     * Specificity: race+class > race > class > generic.
     */
    fun bestVariant(
        def: SpriteDefinition,
        playerRace: String,
        playerClass: String,
        playerGender: String,
    ): SpriteVariant? {
        val matching = def.variants.filter {
            if (def.category == SpriteCategory.STAFF) {
                true
            } else {
                it.matchesPlayer(playerRace, playerClass, playerGender)
            }
        }
        if (matching.isEmpty()) return null

        // Score by specificity: each non-null qualifier adds 1 point
        return matching.maxByOrNull { v ->
            (if (v.race != null) 1 else 0) +
                (if (v.playerClass != null) 1 else 0) +
                (if (v.gender != null) 1 else 0)
        }
    }

    companion object {
        fun isUnlocked(
            def: SpriteDefinition,
            level: Int,
            unlockedAchievementIds: Set<String>,
            isStaff: Boolean,
        ): Boolean =
            when (val cond = def.unlockCondition) {
                is SpriteUnlockCondition.Level -> level >= cond.minLevel
                is SpriteUnlockCondition.Achievement -> cond.achievementId in unlockedAchievementIds
                is SpriteUnlockCondition.Staff -> isStaff
            }
    }
}
