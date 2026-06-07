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
)
