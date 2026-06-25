package dev.ambon.domain.world.data

data class RoomFile(
    val title: String,
    val description: String,
    /**
     * Direction string -> exit target. Supports both string form ("n: room_id")
     * and object form with optional door block. See [ExitValue].
     */
    val exits: Map<String, ExitValue> = emptyMap(),
    /**
     * Non-exit features: containers, levers, signs. Keyed by local feature id.
     * Exit-attached doors are declared inside [exits] entries via [ExitValue.door].
     */
    val features: Map<String, FeatureFile> = emptyMap(),
    /** Crafting station type available in this room (e.g. "forge", "alchemy_table", "workbench"). */
    val station: String? = null,
    /** True if this room has a bank NPC (enables deposit/withdraw commands). */
    val bank: Boolean = false,
    /** True if this room is a tavern (enables gambling commands). */
    val tavern: Boolean = false,
    /** True if this room has a stylist NPC (enables race-change commands). */
    val stylist: Boolean = false,
    /** True if this room has a dungeon portal (enables dungeon kiosk badge). */
    val dungeon: Boolean = false,
    /** True if this room has an auction house (enables auction hall badge). */
    val auction: Boolean = false,
    /** True if this room has a housing broker (enables housing kiosk badge). */
    val housingBroker: Boolean = false,
    /** True if this room is an inn (enables `rest` + recall-point setting). */
    val inn: Boolean = false,
    /** True if this room holds an Akathavae shrine (enables `pledge`/`renounce`). */
    val akathavaeShrine: Boolean = false,
    /** True if this room has a flight master (enables `flights`/`fly` fast-travel + kiosk badge). */
    val flightMaster: Boolean = false,
    /** URL to an image representing this room. */
    val image: String? = null,
    /** URL to a video cinematic for this room. */
    val video: String? = null,
    /** Background music track for this room (overrides zone default). */
    val music: String? = null,
    /** Ambient sound loop for this room (overrides zone default). */
    val ambient: String? = null,
    /** Jukebox playlist for this room. Non-empty enables the jukebox command + badge. */
    val jukebox: List<JukeboxSongFile> = emptyList(),
    /** A single-song music box for this room. Present enables the music-box device + badge. */
    val musicBox: MusicBoxFile? = null,
    /** Terrain type for this room (overrides zone default). Affects weather display and default background. */
    val terrain: String? = null,
)

/**
 * One authored jukebox track. [file] is an audio filename resolved against the
 * zone audio base (like room `music`/`ambient`). [durationSeconds] is the play
 * length the jukebox locks the room for; [cost] (gold) defaults to the configured
 * jukebox cost when omitted. [description] is optional lore flavour, broadcast to
 * the room when the song starts. [lyrics] lines are broadcast to the room spread
 * evenly across the song's duration — flavour for players without audio, so the
 * count must leave at least a few seconds between lines (validated at load).
 */
data class JukeboxSongFile(
    val title: String,
    val file: String,
    val durationSeconds: Int,
    val cost: Long? = null,
    val artist: String? = null,
    val description: String? = null,
    val lyrics: List<String> = emptyList(),
)

/**
 * A room's one-song music box. [file] is an audio filename resolved against the
 * zone audio base (like room `music`). [durationSeconds] is the play length.
 * Playing it is free and player-scoped — the song follows the player out of the
 * room. [lyrics] lines are surfaced on the player's device, spread evenly across
 * the duration, so the count must leave at least a few seconds between lines
 * (validated at load). [artist]/[description] are optional flavour. [image] is an
 * optional filename (resolved against the zone images base, like room/item art)
 * for the collectible lyric-sheet keepsake minted on first play; when omitted the
 * keepsake falls back to the client's generic item default.
 */
data class MusicBoxFile(
    val title: String,
    val file: String,
    val durationSeconds: Int,
    val artist: String? = null,
    val description: String? = null,
    val lyrics: List<String> = emptyList(),
    val image: String? = null,
)
