package dev.ambon.domain

/**
 * Map-based stat container, keyed by uppercase stat ID (e.g. "STR", "DEX").
 *
 * The canonical stat representation for items, races, equipment bonuses,
 * status-effect modifiers, and resolved effective stats.
 */
@JvmInline
value class StatMap(
    val values: Map<String, Int> = emptyMap(),
) {
    /** Returns the value for [id], or 0 if not present. */
    operator fun get(id: String): Int = values[id.uppercase()] ?: 0

    /** Returns a new [StatMap] with [id] set to [value]. */
    fun with(
        id: String,
        value: Int,
    ): StatMap = StatMap(values + (id.uppercase() to value))

    /** Returns a new [StatMap] with all values from [other] added to the corresponding values in this map. */
    operator fun plus(other: StatMap): StatMap {
        if (other.values.isEmpty()) return this
        val merged = values.toMutableMap()
        for ((k, v) in other.values) {
            val key = k.uppercase()
            merged[key] = (merged[key] ?: 0) + v
        }
        return StatMap(merged)
    }

    /** True if all values in this map are zero (or it is empty). */
    fun isZero(): Boolean = values.values.all { it == 0 }

    /** Returns only non-zero entries. */
    fun nonZero(): Map<String, Int> = values.filter { it.value != 0 }

    companion object {
        val EMPTY = StatMap()

        fun of(vararg pairs: Pair<String, Int>): StatMap =
            StatMap(pairs.associate { (k, v) -> k.uppercase() to v })
    }
}
