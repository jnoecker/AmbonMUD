package dev.ambon.domain

data class StatDefinition(
    val id: String,
    val displayName: String,
    val abbreviation: String,
    val description: String = "",
    val baseStat: Int = 10,
)
