package dev.ambon.domain

/** An inclusive min–max damage range shared by mobs and ability effects. */
data class DamageRange(
    val min: Int,
    val max: Int,
)
