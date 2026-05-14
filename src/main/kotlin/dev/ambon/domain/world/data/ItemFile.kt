package dev.ambon.domain.world.data

import dev.ambon.domain.items.ItemUseEffect

data class ItemFile(
    val displayName: String,
    val description: String = "",
    val keyword: String? = null,
    val slot: String? = null,
    val damage: Int = 0,
    val armor: Int = 0,
    val stats: Map<String, Int> = emptyMap(),
    val consumable: Boolean = false,
    val charges: Int? = null,
    val onUse: ItemUseEffect? = null,
    val room: String? = null,
    val mob: String? = null,
    val matchByKey: Boolean = false,
    val basePrice: Int = 0,
    val image: String? = null,
    val video: String? = null,
    val itemType: String? = null,
    val questItem: Boolean = false,
    /**
     * Intended item level. When set together with [rarity] (or by itself), the loader runs
     * the power-budget check defined by `engine.items.budget`. Items without a level are
     * treated as legacy/untunable and skipped by the validator.
     */
    val level: Int? = null,
    /**
     * Rarity tier (common, uncommon, rare, epic, legendary). Multiplies the budget allowed
     * for the slot+level combination. See [dev.ambon.config.ItemBudgetConfig] for the
     * default scale.
     */
    val rarity: String? = null,
)
