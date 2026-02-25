package dev.ambon.domain.world.data

data class DoorFile(
    val initialState: String = "closed",
    val keyItemId: String? = null,
    val keyConsumed: Boolean = false,
    val resetWithZone: Boolean = true,
)
