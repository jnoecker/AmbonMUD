package dev.ambon.domain.world

import dev.ambon.config.ItemBudgetConfig
import dev.ambon.domain.world.data.ItemFile

/**
 * Result of evaluating an authored item against its power budget. [spent] is the total
 * cost of the item's stats/damage/armor; [budget] is what's allowed for its level+slot+rarity.
 * [overBudget] is true when `spent > budget * (1 + tolerance)`.
 */
data class ItemBudgetEvaluation(
    val itemId: String,
    val slot: String,
    val level: Int,
    val rarity: String,
    val spent: Double,
    val budget: Double,
    val tolerance: Double,
    val breakdown: List<String>,
) {
    val overBudget: Boolean get() = spent > budget * (1.0 + tolerance)

    fun summary(): String {
        val pct = if (budget > 0) (spent / budget * 100.0) else Double.POSITIVE_INFINITY
        val pctStr = if (pct.isFinite()) "%.0f%%".format(pct) else "∞"
        return "Item '$itemId' ($slot, lvl $level $rarity) uses %.1f / %.1f budget points ($pctStr): %s"
            .format(spent, budget, breakdown.joinToString(", "))
    }
}

/**
 * Compute a budget evaluation for the given item. Returns null when the item is not
 * subject to budget checks (no slot, or both level and rarity are null on the item file
 * — i.e. the builder hasn't opted in to budget tuning for this piece).
 */
fun evaluateItemBudget(
    itemId: String,
    file: ItemFile,
    config: ItemBudgetConfig,
): ItemBudgetEvaluation? {
    if (!config.enabled) return null
    if (file.level == null && file.rarity == null) return null
    val slot = file.slot?.lowercase()?.takeUnless { it.isEmpty() } ?: return null

    val level = (file.level ?: 1).coerceAtLeast(1)
    val rarity = (file.rarity ?: config.defaultRarity).lowercase()

    val slotBase = config.slotBaseBudget[slot]
        ?: error("Item '$itemId' uses slot '$slot' which has no entry in items.budget.slotBaseBudget")
    val rarityMult = config.rarityMultiplier[rarity]
        ?: error(
            "Item '$itemId' uses rarity '$rarity' which has no entry in items.budget.rarityMultiplier. " +
                "Known rarities: ${config.rarityMultiplier.keys.sorted().joinToString(", ")}",
        )

    val budget = (slotBase + level * config.pointsPerLevel) * rarityMult

    val damagePoints = file.damage * config.damagePointCost
    val armorPoints = file.armor * config.armorPointCost
    val statSum = file.stats.values.sumOf { it }
    val statPoints = statSum * config.statPointCost
    val spent = damagePoints + armorPoints + statPoints

    val breakdown = buildList {
        if (file.damage > 0) add("damage ${file.damage}=%.1fpt".format(damagePoints))
        if (file.armor > 0) add("armor ${file.armor}=%.1fpt".format(armorPoints))
        if (statSum > 0) add("stats $statSum=%.1fpt".format(statPoints))
    }

    return ItemBudgetEvaluation(
        itemId = itemId,
        slot = slot,
        level = level,
        rarity = rarity,
        spent = spent,
        budget = budget,
        tolerance = config.tolerance,
        breakdown = breakdown,
    )
}
