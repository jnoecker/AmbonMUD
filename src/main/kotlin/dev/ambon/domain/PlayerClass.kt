package dev.ambon.domain

enum class PlayerClass(
    val displayName: String,
    val hpPerLevel: Int,
    val manaPerLevel: Int,
    val debugOnly: Boolean = false,
) {
    WARRIOR("Warrior", hpPerLevel = 8, manaPerLevel = 4),
    MAGE("Mage", hpPerLevel = 4, manaPerLevel = 16),
    CLERIC("Cleric", hpPerLevel = 6, manaPerLevel = 12),
    ROGUE("Rogue", hpPerLevel = 5, manaPerLevel = 8),
    SWARM("Swarm", hpPerLevel = 2, manaPerLevel = 3, debugOnly = true),
    ;

    companion object {
        fun fromString(s: String): PlayerClass? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }

        fun selectable(debugClassesEnabled: Boolean = false): List<PlayerClass> =
            entries.filter { !it.debugOnly || debugClassesEnabled }
    }
}
