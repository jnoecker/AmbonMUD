package dev.ambon.domain.world

/**
 * A room's music box — a one-song miniature of the jukebox (a miniaturization of
 * Tessikar's musical device). Authored per-room in the zone YAML (see
 * [dev.ambon.domain.world.data.RoomFile]); the [url] is a fully-resolved audio URL
 * (the WorldLoader prefixes the authored filename with the zone's audio base,
 * exactly like room `music`/`ambient`).
 *
 * Unlike the room-wide jukebox, the music box is **free** and **player-scoped**:
 * playing it costs nothing and the song follows the player out of the room until
 * it ends or they stop it (see [dev.ambon.engine.MusicBoxSystem]).
 *
 * [durationSeconds] is the authored play length. [lyrics] lines are surfaced on
 * the player's device, spread evenly across the duration (the open device scrolls
 * them; while closed they pop as transient toasts so an exploring player can keep
 * up). [description] is optional lore flavour shown on the device.
 */
data class MusicBox(
    val title: String,
    val url: String,
    val durationSeconds: Int,
    val artist: String? = null,
    val description: String? = null,
    val lyrics: List<String> = emptyList(),
)
