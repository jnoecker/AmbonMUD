package dev.ambon.domain.world.data

data class DoorFile(
    val initialState: String = "closed",
    val keyItemId: String? = null,
    val keyConsumed: Boolean = false,
    val resetWithZone: Boolean = true,
    /**
     * Seconds after the door leaves its initial state before it reverts
     * (e.g. an opened door re-closes/re-locks). Null = zone reset only.
     */
    val respawnSeconds: Long? = null,
    /**
     * Optional custom door art for the web client (content-addressed filenames,
     * resolved against the world image base like the lever plate/handle).
     * [frameImage] is the static frame/portal; [leafImage] is the swinging leaf.
     * Absent → the `door_frame`/`door_leaf` global defaults, then a CSS door.
     */
    val frameImage: String? = null,
    val leafImage: String? = null,
    /** Which edge the leaf pivots on: "left" or "right". Default "left". */
    val hinge: String? = null,
    /** Leaf swing angle (degrees) when open; closed is always 0. Default ~100. */
    val openAngle: Double? = null,
)
