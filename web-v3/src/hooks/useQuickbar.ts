import { useCallback, useMemo, useState } from "react";
import type { SkillSummary } from "../types";

const STORAGE_PREFIX = "ambonmud-quickbar";
const DEFAULT_STORAGE_KEY = `${STORAGE_PREFIX}:default`;
const SLOT_COUNT = 9;

type SlotIds = (string | null)[];

function storageKeyFor(characterName: string | null): string {
  if (!characterName || characterName === "-") return DEFAULT_STORAGE_KEY;
  return `${STORAGE_PREFIX}:${characterName.toLowerCase()}`;
}

function emptySlots(): SlotIds {
  return new Array(SLOT_COUNT).fill(null);
}

function loadOrder(key: string): SlotIds {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return emptySlots();
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return emptySlots();
    const slots: SlotIds = parsed.slice(0, SLOT_COUNT).map((v: unknown) =>
      typeof v === "string" && v.length > 0 ? v : null,
    );
    while (slots.length < SLOT_COUNT) slots.push(null);
    return slots;
  } catch {
    return emptySlots();
  }
}

function saveOrder(key: string, order: SlotIds) {
  try {
    localStorage.setItem(key, JSON.stringify(order));
  } catch {
    // localStorage unavailable — fail silently
  }
}

/**
 * Merge current server-known skills with a stored slot order.
 *
 * Rules:
 *  - Stored slot assignments are preserved positionally (hotkeys 1-9 stay stable).
 *  - Slots whose skill is no longer known are cleared (pruned).
 *  - Any server skill not present in the stored order is appended to the first
 *    available empty slot in deterministic (stable) server order, so learning
 *    new skills fills from the left.
 */
function mergeSlotIds(stored: SlotIds, skills: SkillSummary[]): SlotIds {
  const known = new Set(skills.map((s) => s.id));
  const next: SlotIds = stored.map((id) => (id && known.has(id) ? id : null));
  const assigned = new Set(next.filter((id): id is string => id !== null));
  for (const skill of skills) {
    if (assigned.has(skill.id)) continue;
    const emptyIdx = next.indexOf(null);
    if (emptyIdx === -1) break;
    next[emptyIdx] = skill.id;
    assigned.add(skill.id);
  }
  return next;
}

function slotsEqual(a: SlotIds, b: SlotIds): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) return false;
  }
  return true;
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

interface InternalState {
  storageKey: string;
  slotIds: SlotIds;
  // Snapshot of the last `skills` array reference we merged, so we don't
  // re-merge on every render when the server skill list hasn't changed.
  mergedSkillsRef: SkillSummary[] | null;
}

export function useQuickbar(
  skills: SkillSummary[],
  characterName: string | null = null,
): QuickbarState {
  const storageKey = storageKeyFor(characterName);

  const [internal, setInternal] = useState<InternalState>(() => ({
    storageKey,
    slotIds: loadOrder(storageKey),
    mergedSkillsRef: null,
  }));

  // Derive-during-render pattern (https://react.dev/reference/react/useState#storing-information-from-previous-renders).
  // If the character changed OR the server skill list changed, recompute the
  // canonical slot order and schedule a state update before returning.
  let current = internal;
  if (current.storageKey !== storageKey) {
    current = {
      storageKey,
      slotIds: loadOrder(storageKey),
      mergedSkillsRef: null,
    };
  }
  if (current.mergedSkillsRef !== skills) {
    const merged = mergeSlotIds(current.slotIds, skills);
    if (!slotsEqual(current.slotIds, merged)) {
      saveOrder(current.storageKey, merged);
      current = { ...current, slotIds: merged, mergedSkillsRef: skills };
    } else {
      current = { ...current, mergedSkillsRef: skills };
    }
  }
  if (current !== internal) {
    setInternal(current);
  }

  const skillMap = useMemo(() => {
    const m = new Map<string, SkillSummary>();
    for (const s of skills) m.set(s.id, s);
    return m;
  }, [skills]);

  const effectiveSlotIds = current.slotIds;

  const slots = useMemo(
    () => effectiveSlotIds.map((id) => (id ? skillMap.get(id) ?? null : null)),
    [effectiveSlotIds, skillMap],
  );

  const assign = useCallback((slotIndex: number, skillId: string) => {
    if (slotIndex < 0 || slotIndex >= SLOT_COUNT) return;
    setInternal((prev) => {
      const next = [...prev.slotIds];
      // Remove this skill from any other slot first so it only occupies one.
      for (let i = 0; i < next.length; i++) {
        if (next[i] === skillId) next[i] = null;
      }
      next[slotIndex] = skillId;
      saveOrder(prev.storageKey, next);
      return { ...prev, slotIds: next };
    });
  }, []);

  const clear = useCallback((slotIndex: number) => {
    if (slotIndex < 0 || slotIndex >= SLOT_COUNT) return;
    setInternal((prev) => {
      if (prev.slotIds[slotIndex] === null) return prev;
      const next = [...prev.slotIds];
      next[slotIndex] = null;
      saveOrder(prev.storageKey, next);
      return { ...prev, slotIds: next };
    });
  }, []);

  const swap = useCallback((fromIndex: number, toIndex: number) => {
    if (fromIndex === toIndex) return;
    if (fromIndex < 0 || fromIndex >= SLOT_COUNT) return;
    if (toIndex < 0 || toIndex >= SLOT_COUNT) return;
    setInternal((prev) => {
      const next = [...prev.slotIds];
      [next[fromIndex], next[toIndex]] = [next[toIndex], next[fromIndex]];
      saveOrder(prev.storageKey, next);
      return { ...prev, slotIds: next };
    });
  }, []);

  return { slots, assign, clear, swap, slotIds: effectiveSlotIds };
}
