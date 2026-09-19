import { describe, expect, test } from "bun:test";
import { deriveQuestTargets, isQuestTargetItem, isQuestTargetMob } from "../src/canvas/questTargets";
import type { QuestEntry } from "../src/types";

const quests: QuestEntry[] = [
  {
    id: "caves:menace",
    name: "Goblin Menace",
    description: "",
    objectives: [
      { description: "Slay 5 goblins", current: 2, required: 5, type: "kill", targetId: "caves:goblin" },
      { description: "Slay the chief", current: 1, required: 1, type: "kill", targetId: "caves:goblin_chief" },
      { description: "Collect 3 cups of tea", current: 0, required: 3, type: "collect", targetId: "town:cup_of_tea" },
    ],
  },
];

describe("quest targets", () => {
  test("unfinished kill and collect objectives become target sets", () => {
    const t = deriveQuestTargets(quests);
    expect([...t.mobKeys]).toEqual(["caves:goblin"]);
    expect([...t.itemIds]).toEqual(["town:cup_of_tea"]);
  });

  test("a finished objective no longer marks its target", () => {
    const t = deriveQuestTargets(quests);
    expect(isQuestTargetMob(t, "caves:goblin_chief")).toBe(false);
    expect(isQuestTargetMob(t, "caves:goblin")).toBe(true);
    expect(isQuestTargetMob(t, "")).toBe(false);
  });

  test("collect targets match exactly or by local-id suffix, like the server", () => {
    const t = deriveQuestTargets(quests);
    expect(isQuestTargetItem(t, "town:cup_of_tea")).toBe(true);
    expect(isQuestTargetItem(t, "harbor:cup_of_tea")).toBe(true);
    expect(isQuestTargetItem(t, "town:teapot")).toBe(false);
  });

  test("objectives from older servers without type/target mark nothing", () => {
    const t = deriveQuestTargets([{ id: "q", name: "Q", description: "", objectives: [{ description: "x", current: 0, required: 1 }] }]);
    expect(t.mobKeys.size).toBe(0);
    expect(t.itemIds.size).toBe(0);
  });
});
