package dev.ambon.domain.crafting

enum class CraftingSkill {
    MINING,
    HERBALISM,
    SMITHING,
    ALCHEMY,
    ;

    val isGathering: Boolean get() = this == MINING || this == HERBALISM
    val isCrafting: Boolean get() = this == SMITHING || this == ALCHEMY
}
