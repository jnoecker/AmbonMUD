package dev.ambon.domain

enum class Race(
    val displayName: String,
) {
    HUMAN("Human"),
    ;

    companion object {
        fun fromString(s: String): Race? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}
