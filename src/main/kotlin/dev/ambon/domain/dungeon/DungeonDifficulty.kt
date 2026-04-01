package dev.ambon.domain.dungeon

/** Selectable difficulty tier for dungeon runs. */
enum class DungeonDifficulty(
    val displayName: String,
    val hpMultiplier: Double,
    val damageMultiplier: Double,
    val xpMultiplier: Double,
    val dropRateMultiplier: Double,
) {
    LORE("Lore", 0.5, 0.5, 0.0, 0.0),
    NORMAL("Normal", 1.0, 1.0, 1.0, 1.0),
    HARD("Hard", 1.5, 1.5, 1.5, 1.5),
    HEROIC("Heroic", 2.0, 2.0, 2.0, 2.0),
    ;

    companion object {
        fun fromName(name: String): DungeonDifficulty? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
