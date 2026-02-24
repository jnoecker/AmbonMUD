package dev.ambon.domain

enum class PlayerClass(
    val displayName: String,
) {
    ADVENTURER("Adventurer"),
    ;

    companion object {
        fun fromString(s: String): PlayerClass? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}
