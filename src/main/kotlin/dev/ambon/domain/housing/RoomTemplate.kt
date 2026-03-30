package dev.ambon.domain.housing

/**
 * A room template defines a type of room that can exist inside a player's house.
 *
 * Templates are loaded from configuration and are immutable at runtime.
 * Players purchase room instances based on these templates.
 *
 * @property id unique template identifier (e.g. "cottage_entry", "vault", "workshop").
 * @property title default room title shown to players.
 * @property description default room description.
 * @property cost gold required to purchase this room.
 * @property isEntry true if this is the initial room purchased when buying a house.
 *           Exactly one template should be marked as entry.
 * @property image optional image path for the room.
 * @property maxDroppedItems when > 0, items dropped in this room persist across sessions.
 *           Acts as a vault/storage room. 0 means dropped items are transient (cleared on logout).
 * @property safe when true, combat cannot be initiated in this room.
 * @property station optional crafting station type available in this room.
 */
data class RoomTemplate(
    val id: String,
    val title: String,
    val description: String,
    val cost: Long,
    val isEntry: Boolean = false,
    val image: String? = null,
    val maxDroppedItems: Int = 0,
    val safe: Boolean = false,
    val station: String? = null,
)
