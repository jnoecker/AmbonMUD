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
)
