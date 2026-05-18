import type { ItemSummary } from "../types";

export type PotionKind = "hp" | "mana";

export interface QuickPotionPick {
  item: ItemSummary;
  /** How much that potion would restore for the chosen kind. */
  amount: number;
  /** True if the chosen potion would fully restore the missing amount. */
  fullyRestores: boolean;
}

/**
 * Selects the least powerful potion in [inventory] that fully restores [missing] for [kind].
 * Falls back to the most powerful potion when none fully restore. Returns null when no candidate
 * exists or the player isn't missing any of the resource.
 */
export function selectQuickPotion(
  inventory: readonly ItemSummary[],
  missing: number,
  kind: PotionKind,
): QuickPotionPick | null {
  if (missing <= 0) return null;
  const candidates: { item: ItemSummary; amount: number }[] = [];
  for (const item of inventory) {
    if (!item.consumable) continue;
    const amount = kind === "hp" ? item.onUse?.healHp ?? 0 : item.onUse?.healMana ?? 0;
    if (amount > 0) candidates.push({ item, amount });
  }
  if (candidates.length === 0) return null;

  const sufficient = candidates.filter((c) => c.amount >= missing);
  const pool = sufficient.length > 0 ? sufficient : candidates;
  const pick = sufficient.length > 0
    ? pool.reduce((best, cur) => (cur.amount < best.amount ? cur : best))
    : pool.reduce((best, cur) => (cur.amount > best.amount ? cur : best));
  return {
    item: pick.item,
    amount: pick.amount,
    fullyRestores: pick.amount >= missing,
  };
}
