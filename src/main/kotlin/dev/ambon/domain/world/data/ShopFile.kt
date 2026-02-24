package dev.ambon.domain.world.data

data class ShopFile(
    val name: String,
    val room: String,
    val items: List<String> = emptyList(),
)
