package dev.ambon.engine.status

import dev.ambon.domain.StatMap

@JvmInline
value class StatusEffectId(
    val value: String,
)

data class StatusEffectDefinition(
    val id: StatusEffectId,
    val displayName: String,
    val effectType: String,
    val durationMs: Long,
    val tickIntervalMs: Long = 0L,
    val tickMinValue: Int = 0,
    val tickMaxValue: Int = 0,
    val shieldAmount: Int = 0,
    val statMods: StatMap = StatMap.EMPTY,
    val stackBehavior: String = "refresh",
    val maxStacks: Int = 1,
)
