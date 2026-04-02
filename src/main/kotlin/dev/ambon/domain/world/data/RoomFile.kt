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
    /** URL to an image representing this room. */
    val image: String? = null,
    /** URL to a video cinematic for this room. */
    val video: String? = null,
    /** Background music track for this room (overrides zone default). */
    val music: String? = null,
    /** Ambient sound loop for this room (overrides zone default). */
    val ambient: String? = null,
)
