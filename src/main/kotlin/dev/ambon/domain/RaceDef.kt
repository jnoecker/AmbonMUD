package dev.ambon.domain

data class RaceDef(
    val id: String,
    val displayName: String,
    val description: String = "",
    val backstory: String = "",
    val traits: List<String> = emptyList(),
    val abilities: List<String> = emptyList(),
    val image: String = "",
    val statMods: StatMap = StatMap.EMPTY,
    /** Optional race-specific passive ability triggered by low health or a would-be-lethal blow. */
    val racialAbility: RacialAbility? = null,
)
