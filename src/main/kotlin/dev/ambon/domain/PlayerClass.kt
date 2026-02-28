package dev.ambon.domain

enum class PlayerClass(
    val displayName: String,
    val hpPerLevel: Int,
    val manaPerLevel: Int,
) {
    WARRIOR("Warrior", hpPerLevel = 3, manaPerLevel = 2),
    MAGE("Mage", hpPerLevel = 1, manaPerLevel = 8),
    CLERIC("Cleric", hpPerLevel = 2, manaPerLevel = 6),
    ROGUE("Rogue", hpPerLevel = 2, manaPerLevel = 4),
    SWARM("Swarm", hpPerLevel = 2, manaPerLevel = 2),
    ;

    companion object {
        fun fromString(s: String): PlayerClass? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}
