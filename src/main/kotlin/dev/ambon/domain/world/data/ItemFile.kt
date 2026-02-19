package dev.ambon.domain.world.data

data class ItemFile(
    val displayName: String,
    val description: String = "",
    val keyword: String? = null,
    val slot: String? = null,
    val damage: Int = 0,
    val armor: Int = 0,
    val room: String? = null,
    val mob: String? = null,
)
