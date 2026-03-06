import { useCallback, useMemo, useState } from "react";
import type { SkillSummary } from "../types";

const STORAGE_KEY = "ambonmud-quickbar";
const SLOT_COUNT = 9;

function loadOrder(): (string | null)[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return new Array(SLOT_COUNT).fill(null);
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return new Array(SLOT_COUNT).fill(null);
    const slots = parsed.slice(0, SLOT_COUNT).map((v: unknown) =>
      typeof v === "string" ? v : null,
    );
    while (slots.length < SLOT_COUNT) slots.push(null);
    return slots;
  } catch {
    return new Array(SLOT_COUNT).fill(null);
  }
}

function saveOrder(order: (string | null)[]) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(order));
}

export interface QuickbarState {
  /** The resolved skills for each slot (null = empty slot). */
  slots: (SkillSummary | null)[];
  /** Assign a skill to a slot index (0-based). */
  assign: (slotIndex: number, skillId: string) => void;
  /** Clear a slot. */
  clear: (slotIndex: number) => void;
  /** Swap two slots. */
  swap: (fromIndex: number, toIndex: number) => void;
  /** The raw slot order (skill IDs or null). */
  slotIds: (string | null)[];
}

export function useQuickbar(skills: SkillSummary[]): QuickbarState {
  const [slotIds, setSlotIds] = useState<(string | null)[]>(loadOrder);

  const skillMap = useMemo(() => {
    const m = new Map<string, SkillSummary>();
    for (const s of skills) m.set(s.id, s);
    return m;
  }, [skills]);

  // Resolve each slot to its skill (or null if unassigned / skill not known)
  const slots = useMemo(() => {
    // If no custom order has been set yet (all null), auto-fill from skills
    const hasAny = slotIds.some((id) => id !== null);
    if (!hasAny && skills.length > 0) {
      return skills.slice(0, SLOT_COUNT).map((s) => s as SkillSummary | null)
        .concat(new Array(Math.max(0, SLOT_COUNT - skills.length)).fill(null));
    }
    return slotIds.map((id) => (id ? skillMap.get(id) ?? null : null));
  }, [slotIds, skillMap, skills]);

  const assign = useCallback((slotIndex: number, skillId: string) => {
    setSlotIds((prev) => {
      const next = [...prev];
      // Remove this skill from any other slot first
      for (let i = 0; i < next.length; i++) {
        if (next[i] === skillId) next[i] = null;
      }
      next[slotIndex] = skillId;
      saveOrder(next);
      return next;
    });
  }, []);

  const clear = useCallback((slotIndex: number) => {
    setSlotIds((prev) => {
      const next = [...prev];
      next[slotIndex] = null;
      saveOrder(next);
      return next;
    });
  }, []);

  const swap = useCallback((fromIndex: number, toIndex: number) => {
    if (fromIndex === toIndex) return;
    setSlotIds((prev) => {
      const next = [...prev];
      [next[fromIndex], next[toIndex]] = [next[toIndex], next[fromIndex]];
      saveOrder(next);
      return next;
    });
  }, []);

  return { slots, assign, clear, swap, slotIds };
}
