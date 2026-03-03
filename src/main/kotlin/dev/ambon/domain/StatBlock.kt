package dev.ambon.domain

/**
 * Canonical representation of the six core ability-score fields.
 * Used for item bonuses, race modifiers, equipment totals, status-effect
 * modifiers, and resolved effective stats.
 */
data class StatBlock(
    val str: Int = 0,
    val dex: Int = 0,
    val con: Int = 0,
    val int: Int = 0,
    val wis: Int = 0,
    val cha: Int = 0,
) {
    operator fun plus(other: StatBlock): StatBlock =
        StatBlock(
            str = str + other.str,
            dex = dex + other.dex,
            con = con + other.con,
            int = int + other.int,
            wis = wis + other.wis,
            cha = cha + other.cha,
        )

    companion object {
        val ZERO = StatBlock()
    }
}
