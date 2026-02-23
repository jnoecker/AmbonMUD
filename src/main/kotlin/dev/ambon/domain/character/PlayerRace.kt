package dev.ambon.domain.character

/**
 * Player races. Races provide minor flat stat bonuses.
 */
enum class PlayerRace(
    val displayName: String,
    val shortName: String,
    val hpBonus: Int,
    val manaBonus: Int,
) {
    HUMAN("Human", "H", hpBonus = 0, manaBonus = 0),
    FAERIE("Faerie", "F", hpBonus = -2, manaBonus = 5),
    TROLL("Troll", "T", hpBonus = 5, manaBonus = -2),
    ETHEREAL("Ethereal", "E", hpBonus = 0, manaBonus = 3),
    ;

    companion object {
        fun fromString(value: String): PlayerRace? =
            entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) ||
                    it.displayName.equals(value, ignoreCase = true) ||
                    it.shortName.equals(value, ignoreCase = true)
            }

        fun selectionPrompt(): String = entries.joinToString(", ") { "(${it.shortName})${it.displayName.drop(1)}" }
    }
}
