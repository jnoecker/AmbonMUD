package dev.ambon.domain.world

import dev.ambon.domain.ids.ItemId

data class MobDrop(
    val itemId: ItemId,
    val chance: Double,
)
