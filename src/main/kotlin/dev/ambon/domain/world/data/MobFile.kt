package dev.ambon.domain.world.data

data class MobFile(
    val name: String,
    val room: String,
    val tier: String? = null,
    val level: Int? = null,
    val hp: Int? = null,
    val minDamage: Int? = null,
    val maxDamage: Int? = null,
    val armor: Int? = null,
    val xpReward: Long? = null,
    val drops: List<MobDropFile> = emptyList(),
    val respawnSeconds: Long? = null,
    val goldMin: Long? = null,
    val goldMax: Long? = null,
    val stationary: Boolean = false,
    val dialogue: Map<String, DialogueNodeFile> = emptyMap(),
)
