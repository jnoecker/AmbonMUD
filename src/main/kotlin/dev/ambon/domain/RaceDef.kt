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
)
