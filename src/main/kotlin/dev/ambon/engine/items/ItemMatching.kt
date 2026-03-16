package dev.ambon.engine.items

import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.mob.MobState

private const val SUBSTRING_MATCH_MIN_LENGTH = 3

internal enum class ItemMatchMode {
    EXACT,
    SUBSTRING,
    ;

    fun matches(
        item: Item,
        input: String,
    ): Boolean =
        when (this) {
            EXACT -> item.keyword.equals(input, ignoreCase = true)
            SUBSTRING ->
                !item.matchByKey &&
                    (
                        item.displayName.contains(input, ignoreCase = true) ||
                            item.description.contains(input, ignoreCase = true)
                    )
        }
}

internal fun itemMatchModes(input: String): List<ItemMatchMode> =
    if (input.length >= SUBSTRING_MATCH_MIN_LENGTH) {
        listOf(ItemMatchMode.EXACT, ItemMatchMode.SUBSTRING)
    } else {
        listOf(ItemMatchMode.EXACT)
    }

internal fun findMatchingItemIndex(
    items: List<ItemInstance>,
    input: String,
): Int {
    for (mode in itemMatchModes(input)) {
        val idx = items.indexOfFirst { mode.matches(it.item, input) }
        if (idx >= 0) return idx
    }
    return -1
}

// ── Public keyword matching extensions ──────────────────────────────────────

/**
 * Returns true if [input] matches this item's keyword (exact, case-insensitive)
 * or, for inputs of 3+ characters, its display name or description (substring,
 * case-insensitive). Items with [Item.matchByKey] = true skip substring matching.
 *
 * This is the canonical matching logic — use it instead of ad-hoc comparisons.
 */
fun ItemInstance.matchesKeyword(input: String): Boolean =
    itemMatchModes(input).any { it.matches(item, input) }

/** Same as [ItemInstance.matchesKeyword] but operates on a bare [Item]. */
fun Item.matchesKeyword(input: String): Boolean =
    itemMatchModes(input).any { it.matches(this, input) }

/**
 * Returns true if [input] matches this mob's name by exact match (case-insensitive)
 * or, for inputs of 3+ characters, by substring in the name or mob ID's local part.
 */
fun MobState.matchesKeyword(input: String): Boolean {
    if (name.equals(input, ignoreCase = true)) return true
    if (input.length < SUBSTRING_MATCH_MIN_LENGTH) return false
    if (name.contains(input, ignoreCase = true)) return true
    val local = id.value.substringAfter(':', id.value)
    return local.contains(input, ignoreCase = true)
}
