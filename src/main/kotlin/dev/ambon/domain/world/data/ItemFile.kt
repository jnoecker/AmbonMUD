package dev.ambon.domain.world.data

data class ItemFile(
    val displayName: String,
    val description: String = "",
    val keyword: String? = null,
    val room: String? = null,
    val mob: String? = null,
)
