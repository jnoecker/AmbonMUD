package dev.ambon.domain

data class PlayerClassDef(
    val id: String,
    val displayName: String,
    val hpPerLevel: Int,
    val manaPerLevel: Int,
    val description: String = "",
    val selectable: Boolean = true,
    val primaryStat: String? = null,
    val startRoom: String? = null,
)
