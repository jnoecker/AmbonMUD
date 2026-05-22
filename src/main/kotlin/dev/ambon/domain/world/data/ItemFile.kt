package dev.ambon.domain.world.data

import dev.ambon.domain.items.ItemUseEffect

data class ItemFile(
    val displayName: String,
    val description: String = "",
    val keyword: String? = null,
    val slot: String? = null,
    /** Class-restriction list. Null = unrestricted (current behaviour). Currently ignored by the engine. */
    val classes: List<String>? = null,
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
    val takeable: Boolean = true,
    /**
     * Optional design-time metadata authored by the Arcanum creator. Accepted
     * for round-trip preservation; the server treats `damage`, `armor`, and
     * `stats` above as authoritative and does not recompute them from
     * `level`/`tier`/`archetype`.
     */
    val level: Int? = null,
    val tier: String? = null,
    val archetype: String? = null,
    val primaryStat: String? = null,
    val secondaryStat: String? = null,
    val tertiaryStat: String? = null,
)
