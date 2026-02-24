package dev.ambon.domain.world.data

data class ItemOnUseFile(
    val healHp: Int = 0,
    val grantXp: Long = 0L,
)

data class ItemFile(
    val displayName: String,
    val description: String = "",
    val keyword: String? = null,
    val slot: String? = null,
    val damage: Int = 0,
    val armor: Int = 0,
    val constitution: Int = 0,
    val strength: Int = 0,
    val dexterity: Int = 0,
    val intelligence: Int = 0,
    val wisdom: Int = 0,
    val charisma: Int = 0,
    val consumable: Boolean = false,
    val charges: Int? = null,
    val onUse: ItemOnUseFile? = null,
    val room: String? = null,
    val mob: String? = null,
    val matchByKey: Boolean = false,
)
