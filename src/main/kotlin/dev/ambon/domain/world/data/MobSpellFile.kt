package dev.ambon.domain.world.data

data class MobSpellFile(
    val displayName: String = "",
    val message: String = "",
    val roomMessage: String = "",
    val minDamage: Int? = null,
    val maxDamage: Int? = null,
    val healMin: Int = 0,
    val healMax: Int = 0,
    val statusEffectId: String? = null,
    val cooldownMs: Long = 0L,
    val weight: Int = 1,
)
