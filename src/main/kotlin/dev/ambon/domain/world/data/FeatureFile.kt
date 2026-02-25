package dev.ambon.domain.world.data

/**
 * YAML representation of a non-exit room feature (CONTAINER, LEVER, or SIGN).
 * Exit-attached doors use [DoorFile] inside [ExitValue] instead.
 */
data class FeatureFile(
    val type: String,
    val displayName: String,
    val keyword: String,
    /** "open" | "closed" | "locked" for CONTAINER; "up" | "down" for LEVER. Ignored for SIGN. */
    val initialState: String? = null,
    val keyItemId: String? = null,
    val keyConsumed: Boolean = false,
    val resetWithZone: Boolean = true,
    /** Initial item IDs inside this container. Only applies to CONTAINER type. */
    val items: List<String> = emptyList(),
    /** Text content. Only applies to SIGN type. */
    val text: String? = null,
)
