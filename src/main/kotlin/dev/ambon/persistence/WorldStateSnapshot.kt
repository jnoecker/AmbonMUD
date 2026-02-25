package dev.ambon.persistence

/**
 * Serializable snapshot of all mutable world feature states.
 * Keys are feature IDs in the format "<zone>:<room>/<localId>".
 */
data class WorldStateSnapshot(
    /** Door states: featureId → DoorState name (OPEN/CLOSED/LOCKED). */
    val doorStates: Map<String, String> = emptyMap(),
    /** Container states: featureId → ContainerState name (OPEN/CLOSED/LOCKED). */
    val containerStates: Map<String, String> = emptyMap(),
    /** Lever states: featureId → LeverState name (UP/DOWN). */
    val leverStates: Map<String, String> = emptyMap(),
    /** Container contents: featureId → list of ItemId values currently inside. */
    val containerItems: Map<String, List<String>> = emptyMap(),
)
