package dev.ambon.domain.world

/**
 * One selectable track on a room's jukebox.
 *
 * Authored per-room in the zone YAML (see [dev.ambon.domain.world.data.RoomFile]).
 * The [url] is a fully-resolved audio URL (the WorldLoader prefixes the authored
 * filename with the zone's audio base, exactly like room `music`/`ambient`).
 *
 * [durationSeconds] is the authored play length — the jukebox locks the room to
 * this track for that long, then reverts to the room's default music, so a song
 * effectively "plays once" without the client needing to report track-end.
 * [description] is lore flavour shown in the song list / web panel
 * (e.g. "Raucous Pub Song About Aineroia's Ascension").
 */
data class JukeboxSong(
    val title: String,
    val url: String,
    val durationSeconds: Int,
    val cost: Long,
    val artist: String? = null,
    val description: String? = null,
)
