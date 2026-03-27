package dev.ambon.domain.sprite

/**
 * A sprite that can be unlocked and chosen by a player.
 *
 * Each definition has one or more [variants] with optional race/class/gender
 * qualifiers. A player sees only the variants whose qualifiers match (or are
 * null, meaning "any").
 */
data class SpriteDefinition(
    val id: String,
    val displayName: String,
    val category: SpriteCategory,
    val unlockCondition: SpriteUnlockCondition,
    val sortOrder: Int = 0,
    val variants: List<SpriteVariant>,
)

enum class SpriteCategory {
    TIER,
    ACHIEVEMENT,
    STAFF,
}

sealed interface SpriteUnlockCondition {
    /** Unlocked when the player reaches [minLevel]. */
    data class Level(
        val minLevel: Int,
    ) : SpriteUnlockCondition

    /** Unlocked when the player earns achievement [achievementId]. */
    data class Achievement(
        val achievementId: String,
    ) : SpriteUnlockCondition

    /** Unlocked for staff members only. */
    data object Staff : SpriteUnlockCondition
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
