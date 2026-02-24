package dev.ambon.engine.status

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

data class StatModifiers(
    val str: Int = 0,
    val dex: Int = 0,
    val con: Int = 0,
    val int: Int = 0,
    val wis: Int = 0,
    val cha: Int = 0,
) {
    operator fun plus(other: StatModifiers): StatModifiers =
        StatModifiers(
            str = str + other.str,
            dex = dex + other.dex,
            con = con + other.con,
            int = int + other.int,
            wis = wis + other.wis,
            cha = cha + other.cha,
        )

    companion object {
        val ZERO = StatModifiers()
    }
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
    val statMods: StatModifiers = StatModifiers.ZERO,
    val stackBehavior: StackBehavior = StackBehavior.REFRESH,
    val maxStacks: Int = 1,
)
