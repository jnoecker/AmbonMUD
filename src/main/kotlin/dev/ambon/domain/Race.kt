package dev.ambon.domain

enum class Race(
    val displayName: String,
    val statMods: StatBlock,
) {
    HUMAN("Human", StatBlock(str = 1, cha = 1)),
    ELF("Elf", StatBlock(str = -1, dex = 2, con = -2, int = 1)),
    DWARF("Dwarf", StatBlock(str = 1, dex = -1, con = 2, wis = 1, cha = -2)),
    HALFLING("Halfling", StatBlock(str = -2, dex = 2, con = -1, wis = 1, cha = 1)),
    ;

    companion object {
        fun fromString(s: String): Race? = enumFromString(s)
    }
}
