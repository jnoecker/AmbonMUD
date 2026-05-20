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

    /**
     * Returns a new [StatMap] with archetypal keys ("PRIMARY", "SECONDARY",
     * "TERTIARY") resolved to concrete stat IDs via [priorities]. Concrete
     * stat IDs pass through unchanged. Archetypal entries with no matching
     * priority slot are dropped — an item that grants PRIMARY on a class with
     * no priorities simply contributes nothing rather than crashing.
     *
     * Stats resolved to the same concrete ID are summed (e.g. an item with
     * both `PRIMARY: 2` and `strength: 1` on a STR-primary class yields
     * `STR: 3`).
     */
    fun resolveArchetypal(priorities: List<String>): StatMap {
        if (values.isEmpty()) return this
        val resolved = mutableMapOf<String, Int>()
        for ((key, value) in values) {
            val concrete =
                when (key) {
                    ARCHETYPAL_PRIMARY -> priorities.getOrNull(0)
                    ARCHETYPAL_SECONDARY -> priorities.getOrNull(1)
                    ARCHETYPAL_TERTIARY -> priorities.getOrNull(2)
                    else -> key
                } ?: continue
            resolved.merge(concrete, value, Int::plus)
        }
        return StatMap(resolved)
    }

    companion object {
        val EMPTY = StatMap()

        const val ARCHETYPAL_PRIMARY = "PRIMARY"
        const val ARCHETYPAL_SECONDARY = "SECONDARY"
        const val ARCHETYPAL_TERTIARY = "TERTIARY"

        val ARCHETYPAL_KEYS = setOf(ARCHETYPAL_PRIMARY, ARCHETYPAL_SECONDARY, ARCHETYPAL_TERTIARY)

        fun isArchetypal(id: String): Boolean = id.uppercase() in ARCHETYPAL_KEYS

        fun of(vararg pairs: Pair<String, Int>): StatMap =
            StatMap(pairs.associate { (k, v) -> k.uppercase() to v })
    }
}
