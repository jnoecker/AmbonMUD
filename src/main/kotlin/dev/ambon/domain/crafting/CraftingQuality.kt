package dev.ambon.domain.crafting

/** Quality tier for crafted items. Higher tiers are rarer and produce better results. */
enum class CraftingQuality(
    val displayPrefix: String,
    val tier: Int,
) {
    NORMAL("", 0),
    FINE("Fine", 1),
    SUPERIOR("Superior", 2),
    MASTERWORK("Masterwork", 3),
}
