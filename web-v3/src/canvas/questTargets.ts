import type { QuestEntry } from "../types";

/**
 * What the player's active, unfinished objectives are looking for in the
 * world, so the room can mark them. Mirrors the server's matching rules:
 * kill objectives match a mob's template key exactly; collect objectives
 * match an item id exactly or by its `:<localId>` suffix (any zone's copy of
 * the same local item counts — see `matchesCollectTarget`).
 */
export interface QuestTargets {
  /** Mob template keys wanted by unfinished kill objectives. */
  mobKeys: Set<string>;
  /** Item target ids wanted by unfinished collect objectives. */
  itemIds: Set<string>;
}

export const EMPTY_QUEST_TARGETS: QuestTargets = { mobKeys: new Set(), itemIds: new Set() };

export function deriveQuestTargets(quests: readonly QuestEntry[]): QuestTargets {
  const mobKeys = new Set<string>();
  const itemIds = new Set<string>();
  for (const q of quests) {
    for (const o of q.objectives) {
      if (o.current >= o.required || !o.targetId) continue;
      if (o.type === "kill") mobKeys.add(o.targetId);
      else if (o.type === "collect") itemIds.add(o.targetId);
    }
  }
  return { mobKeys, itemIds };
}

export function isQuestTargetMob(targets: QuestTargets, templateKey: string): boolean {
  return templateKey.length > 0 && targets.mobKeys.has(templateKey);
}

export function isQuestTargetItem(targets: QuestTargets, itemId: string): boolean {
  if (targets.itemIds.size === 0) return false;
  if (targets.itemIds.has(itemId)) return true;
  for (const target of targets.itemIds) {
    const local = target.slice(target.lastIndexOf(":") + 1);
    if (itemId.endsWith(`:${local}`)) return true;
  }
  return false;
}
