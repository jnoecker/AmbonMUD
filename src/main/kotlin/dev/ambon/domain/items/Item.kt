package dev.ambon.domain.items

data class Item(
    val keyword: String,
    val displayName: String,
    val description: String = "",
)
