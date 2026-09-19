import type { ItemSummary } from "../../types";

export interface StatDelta {
  /** "damage", "armor", or a stat key such as "STR". */
  key: string;
  /** Short label for the chip. */
  label: string;
  delta: number;
}

export interface EquipmentComparison {
  /** What's worn in the candidate's slot, or null when the slot is empty. */
  worn: ItemSummary | null;
  /** Non-zero differences, candidate minus worn (worn counts as all zeros when the slot is empty). */
  deltas: StatDelta[];
}

const STAT_LABEL: Record<string, string> = { damage: "dmg", armor: "armor" };

/**
 * Compare an inventory item against whatever occupies its equipment slot.
 * Returns null for items that can't be worn. Equal stats produce an empty
 * `deltas` list, which the panel renders as "same as worn".
 */
export function compareToWorn(
  candidate: ItemSummary,
  equipment: Record<string, ItemSummary>,
): EquipmentComparison | null {
  if (!candidate.slot) return null;
  const worn = equipment[candidate.slot] ?? null;
  const deltas: StatDelta[] = [];
  const push = (key: string, mine: number, theirs: number) => {
    const delta = mine - theirs;
    if (delta !== 0) deltas.push({ key, label: STAT_LABEL[key] ?? key, delta });
  };
  push("damage", candidate.damage ?? 0, worn?.damage ?? 0);
  push("armor", candidate.armor ?? 0, worn?.armor ?? 0);
  const statKeys = new Set([...Object.keys(candidate.stats ?? {}), ...Object.keys(worn?.stats ?? {})]);
  for (const key of [...statKeys].sort()) {
    push(key, candidate.stats?.[key] ?? 0, worn?.stats?.[key] ?? 0);
  }
  return { worn, deltas };
}

export function formatDelta(d: StatDelta): string {
  return `${d.delta > 0 ? "+" : "−"}${Math.abs(d.delta)} ${d.label}`;
}
