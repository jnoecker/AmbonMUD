package dev.ambon.engine.items

import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance

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
