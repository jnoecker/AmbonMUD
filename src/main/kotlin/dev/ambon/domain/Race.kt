package dev.ambon.domain

enum class Race(
    val displayName: String,
    val strMod: Int,
    val dexMod: Int,
    val conMod: Int,
    val intMod: Int,
    val wisMod: Int,
    val chaMod: Int,
) {
    HUMAN("Human", strMod = 1, dexMod = 0, conMod = 0, intMod = 0, wisMod = 0, chaMod = 1),
    ELF("Elf", strMod = -1, dexMod = 2, conMod = -2, intMod = 1, wisMod = 0, chaMod = 0),
    DWARF("Dwarf", strMod = 1, dexMod = -1, conMod = 2, intMod = 0, wisMod = 1, chaMod = -2),
    HALFLING("Halfling", strMod = -2, dexMod = 2, conMod = -1, intMod = 0, wisMod = 1, chaMod = 1),
    ;

    companion object {
        fun fromString(s: String): Race? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}
