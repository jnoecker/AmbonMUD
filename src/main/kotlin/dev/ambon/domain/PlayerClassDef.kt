package dev.ambon.domain

data class PlayerClassDef(
    val id: String,
    val displayName: String,
    val hpPerLevel: Int,
    val manaPerLevel: Int,
    val description: String = "",
    val backstory: String = "",
    val image: String = "",
    val selectable: Boolean = true,
    val primaryStat: String? = null,
    val startRoom: String? = null,
    val threatMultiplier: Double = 1.0,
)
