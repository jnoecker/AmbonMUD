package dev.ambon.domain.world.data

import java.util.Collections.emptyMap

data class RoomFile(
    val title: String,
    val description: String,
    // "north" -> "street"
    val exits: MutableMap<String, String> = emptyMap(),
)
