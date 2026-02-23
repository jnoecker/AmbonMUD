package dev.ambon.domain.character

/**
 * Player class archetypes. Each class has distinct HP/mana scaling and base combat bonuses.
 */
enum class PlayerClass(
    val displayName: String,
    val shortName: String,
    val hpPerLevel: Int,
    val manaPerLevel: Int,
    val baseDamageBonus: Int,
    val baseArmorBonus: Int,
) {
    WARRIOR("Warrior", "W", hpPerLevel = 4, manaPerLevel = 1, baseDamageBonus = 2, baseArmorBonus = 1),
    MAGE("Mage", "M", hpPerLevel = 1, manaPerLevel = 8, baseDamageBonus = 0, baseArmorBonus = 0),
    ROGUE("Rogue", "R", hpPerLevel = 2, manaPerLevel = 3, baseDamageBonus = 3, baseArmorBonus = 0),
    CLERIC("Cleric", "C", hpPerLevel = 3, manaPerLevel = 5, baseDamageBonus = 1, baseArmorBonus = 0),
    ;

    companion object {
        fun fromString(value: String): PlayerClass? =
            entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) ||
                    it.displayName.equals(value, ignoreCase = true) ||
                    it.shortName.equals(value, ignoreCase = true)
            }

        fun selectionPrompt(): String = entries.joinToString(", ") { "(${it.shortName})${it.displayName.drop(1)}" }
    }
}
