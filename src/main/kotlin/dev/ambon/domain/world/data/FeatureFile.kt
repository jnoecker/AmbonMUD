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
    /**
     * Seconds after the feature leaves its initial condition before it reverts
     * (state restored; container contents refilled). Null = zone reset only.
     * Not valid for SIGN.
     */
    val respawnSeconds: Long? = null,
    /** Initial item IDs inside this container. Only applies to CONTAINER type. */
    val items: List<String> = emptyList(),
    /** Text content. Only applies to SIGN type. */
    val text: String? = null,
    /**
     * Optional backdrop art (content-addressed filename) for the World Features
     * modal, resolved against the world image base like room/mob/item art.
     * CONTAINER → open-chest backdrop; SIGN → sign-board backdrop; LEVER → the
     * box the lever sits inside. Absent → the server-wide `<type>_bg` default,
     * then a built-in CSS treatment. Valid for CONTAINER, SIGN, and LEVER.
     */
    val backgroundImage: String? = null,
    /** Static base-plate art (content-addressed filename), drawn behind the handle. LEVER only. */
    val plateImage: String? = null,
    /** Rotating handle art (content-addressed filename), neutral upright pose. LEVER only. */
    val handleImage: String? = null,
    /** Handle pivot as a fraction of the handle sprite. LEVER only. Defaults to {0.5, 0.85}. */
    val leverPivot: LeverPivotFile? = null,
    /** Handle rotation in degrees when state = up. LEVER only. Default -28. */
    val upAngle: Double? = null,
    /** Handle rotation in degrees when state = down. LEVER only. Default 28. */
    val downAngle: Double? = null,
)

/** Handle pivot as fractions (0..1) of the handle sprite box. */
data class LeverPivotFile(
    val x: Double = 0.5,
    val y: Double = 0.85,
)
