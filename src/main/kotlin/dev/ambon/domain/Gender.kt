package dev.ambon.domain

enum class Gender(
    val displayName: String,
    val spriteCode: String,
) {
    MALE("Male", "male"),
    FEMALE("Female", "female"),
    ENBY("Enby", "enby"),
    ;

    companion object {
        fun fromString(s: String): Gender? = enumFromString(s)
    }
}
