package dev.ambon.domain.sprite

/**
 * A sprite that can be unlocked and chosen by a player.
 *
 * Each definition has one or more [variants] with optional race/class/gender
 * qualifiers. A player sees only the variants whose qualifiers match (or are
 * null, meaning "any").
 *
 * Unlock logic: if [requirements] is non-empty, ALL requirements must be met.
 * Otherwise falls back to the legacy [unlockCondition].
 */
data class SpriteDefinition(
    val id: String,
    val displayName: String,
    val description: String = "",
    val category: SpriteCategory,
    /** Legacy single-condition unlock. Used when [requirements] is empty. */
    val unlockCondition: SpriteUnlockCondition = SpriteUnlockCondition.Level(1),
    /** New requirements-based unlock (AND logic). Takes precedence over [unlockCondition]. */
    val requirements: List<SpriteRequirement> = emptyList(),
    val sortOrder: Int = 0,
    val variants: List<SpriteVariant>,
) {
    /** Returns true if this sprite is unlocked (in part) by the given achievement. */
    fun isUnlockedByAchievement(achievementId: String): Boolean {
        if (requirements.isNotEmpty()) {
            return requirements.any { it is SpriteRequirement.Achievement && it.achievementId == achievementId }
        }
        return unlockCondition is SpriteUnlockCondition.Achievement &&
            (unlockCondition as SpriteUnlockCondition.Achievement).achievementId == achievementId
    }
}

enum class SpriteCategory {
    TIER,
    ACHIEVEMENT,
    STAFF,

    /** General-purpose sprites that don't fit the legacy categories. */
    GENERAL,
}

/** Legacy single-condition unlock model (retained for backwards compatibility). */
sealed interface SpriteUnlockCondition {
    data class Level(
        val minLevel: Int,
    ) : SpriteUnlockCondition

    data class Achievement(
        val achievementId: String,
    ) : SpriteUnlockCondition

    data object Staff : SpriteUnlockCondition
}

/**
 * A single requirement that must be met for a sprite to be unlocked.
 * Multiple requirements on a sprite are combined with AND logic.
 */
sealed interface SpriteRequirement {
    /** Player level must be >= [level]. */
    data class MinLevel(
        val level: Int,
    ) : SpriteRequirement

    /** Player race must match (case-insensitive). */
    data class Race(
        val race: String,
    ) : SpriteRequirement

    /** Player class must match (case-insensitive). */
    data class PlayerClass(
        val playerClass: String,
    ) : SpriteRequirement

    /** Player must have unlocked the given achievement. */
    data class Achievement(
        val achievementId: String,
    ) : SpriteRequirement

    /** Player must be staff. */
    data object Staff : SpriteRequirement

    fun isMet(
        playerLevel: Int,
        playerRace: String,
        playerClass: String,
        unlockedAchievementIds: Set<String>,
        isStaff: Boolean,
    ): Boolean = when (this) {
        is MinLevel -> playerLevel >= level
        is Race -> race.equals(playerRace, ignoreCase = true)
        is PlayerClass -> this.playerClass.equals(playerClass, ignoreCase = true)
        is Achievement -> achievementId in unlockedAchievementIds
        is Staff -> isStaff
    }
}

/**
 * A single image variant within a [SpriteDefinition].
 *
 * The [imageId] serves as both the player-facing name (used in `sprite set`)
 * and the file-name stem (image file = `player_sprites/{imageId}.png`).
 *
 * Qualifier fields ([race], [playerClass], [gender]) restrict which players
 * can see and select this variant. `null` means "any".
 */
data class SpriteVariant(
    val imageId: String,
    val displayName: String,
    val race: String? = null,
    val playerClass: String? = null,
    val gender: String? = null,
    val imagePath: String,
) {
    /** Returns `true` if this variant is usable by a player with the given attributes. */
    fun matchesPlayer(
        playerRace: String,
        playerClass: String,
        playerGender: String,
    ): Boolean =
        (race == null || race.equals(playerRace, ignoreCase = true)) &&
            (this.playerClass == null || this.playerClass.equals(playerClass, ignoreCase = true)) &&
            (gender == null || gender.equals(playerGender, ignoreCase = true))
}
