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
    /**
     * Seconds after this room-placed item leaves its room before it respawns
     * there. Null = the item only returns on zone reset. Requires `room`.
     */
    val respawnSeconds: Long? = null,
    val mob: String? = null,
    val matchByKey: Boolean = false,
    val basePrice: Int = 0,
    val image: String? = null,
    val video: String? = null,
    val itemType: String? = null,
    val questItem: Boolean = false,
    val takeable: Boolean = true,
    /**
     * Mount id unlocked by buying this item. Required when `itemType: mount`,
     * forbidden otherwise. Must match a mount-requirement sprite definition
     * in sprites.yaml.
     */
    val mountId: String? = null,
    /**
     * Ride-speed multiplier for `itemType: mount` items (1.0 = the configured
     * base pace, 2.0 = twice as fast). Defaults to 1.0; forbidden on non-mounts.
     */
    val mountSpeed: Double? = null,
    /**
     * True for `itemType: mount` items whose mount can fly: cross-zone travel
     * to any explored room via the world map atlas. Forbidden on non-mounts.
     */
    val flying: Boolean? = null,
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
