package dev.ambon.domain.guild

import dev.ambon.persistence.PlayerId

data class GuildRecord(
    val id: String,
    val name: String,
    val tag: String,
    val leaderId: PlayerId,
    val motd: String? = null,
    val members: Map<PlayerId, String> = emptyMap(),
    val createdAtEpochMs: Long,
    val hallRooms: List<GuildHallRoom> = emptyList(),
)

/**
 * A single room within a guild hall.
 *
 * @property id unique id for this room within the hall (e.g. "room_0").
 * @property template the template key (e.g. "meeting_hall", "vault").
 * @property title display title (defaults from template, may be customised).
 * @property description display description (defaults from template).
 * @property customDescription optional player-set description override.
 */
data class GuildHallRoom(
    val id: String,
    val template: String,
    val title: String,
    val description: String,
    val customDescription: String? = null,
)
