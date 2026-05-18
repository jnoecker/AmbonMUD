package dev.ambon.domain

data class PlayerClassDef(
    val id: String,
    val displayName: String,
    val hpScalingRate: Double,
    val manaScalingRate: Double,
    val description: String = "",
    val backstory: String = "",
    val image: String = "",
    val selectable: Boolean = true,
    val primaryStat: String? = null,
    val startRoom: String? = null,
    val threatMultiplier: Double = 1.0,
    val starterEquipment: List<StarterEquipmentEntry> = emptyList(),
)

data class StarterEquipmentEntry(
    val itemId: String,
    val equip: Boolean = true,
)
