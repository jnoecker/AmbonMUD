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
    val statPriorities: List<String> = emptyList(),
    val startRoom: String? = null,
    val threatMultiplier: Double = 1.0,
    val starterEquipment: List<StarterEquipmentEntry> = emptyList(),
) {
    /**
     * Effective archetypal priority list used to resolve PRIMARY/SECONDARY/
     * TERTIARY stat keys on items. Prefers explicit [statPriorities]; falls
     * back to a single-element list derived from [primaryStat] so legacy class
     * YAMLs (which only declare a primaryStat) still get a PRIMARY mapping.
     */
    fun effectiveStatPriorities(): List<String> =
        if (statPriorities.isNotEmpty()) {
            statPriorities
        } else {
            listOfNotNull(primaryStat?.takeIf { it.isNotBlank() }?.uppercase())
        }
}

data class StarterEquipmentEntry(
    val itemId: String,
    val equip: Boolean = true,
)
