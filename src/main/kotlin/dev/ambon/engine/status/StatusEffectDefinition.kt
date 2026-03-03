package dev.ambon.engine.status

import dev.ambon.domain.StatBlock

@JvmInline
value class StatusEffectId(
    val value: String,
)

enum class EffectType {
    DOT,
    HOT,
    STAT_BUFF,
    STAT_DEBUFF,
    STUN,
    ROOT,
    SHIELD,
}

enum class StackBehavior {
    /** Reapplication refreshes the duration. */
    REFRESH,

    /** Each application adds an independent stack (up to maxStacks). */
    STACK,

    /** Reapplication is ignored while the effect is active. */
    NONE,
}

data class StatusEffectDefinition(
    val id: StatusEffectId,
    val displayName: String,
    val effectType: EffectType,
    val durationMs: Long,
    val tickIntervalMs: Long = 0L,
    val tickMinValue: Int = 0,
    val tickMaxValue: Int = 0,
    val shieldAmount: Int = 0,
    val statMods: StatBlock = StatBlock.ZERO,
    val stackBehavior: StackBehavior = StackBehavior.REFRESH,
    val maxStacks: Int = 1,
)
