package dev.ambon.domain

data class RaceDef(
    val id: String,
    val displayName: String,
    val description: String = "",
    val statMods: StatBlock = StatBlock.ZERO,
)
