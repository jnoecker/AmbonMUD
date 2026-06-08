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
    /** Fixed edge the leaf is hinged on: "left" or "right". Default "right". */
    val hinge: String? = null,
    /** Leaf swing angle (degrees, unsigned) when open; closed is always 0. Default ~60. */
    val openAngle: Double? = null,
    /** Leaf size as a fraction of the frame box, to fit it inside the opening. Default 0.76. */
    val leafScale: Double? = null,
    /** Vertical placement of the leaf within the frame (fraction; + = down). Default 0.09. */
    val leafOffsetY: Double? = null,
)
