import { describe, expect, test } from "bun:test";
import { applyGmcpPackage } from "../src/gmcp/applyGmcpPackage";
import type { MobInfo, RoomMob } from "../src/types";

function roomCtx(initialInfo: MobInfo[] = []) {
  let mobs: RoomMob[] = [];
  let mobInfo = initialInfo;
  const ctx = {
    setMobs: (next: RoomMob[] | ((prev: RoomMob[]) => RoomMob[])) => {
      mobs = typeof next === "function" ? next(mobs) : next;
    },
    setMobInfo: (next: MobInfo[] | ((prev: MobInfo[]) => MobInfo[])) => {
      mobInfo = typeof next === "function" ? next(mobInfo) : next;
    },
  } as unknown as Parameters<typeof applyGmcpPackage>[2];
  return { ctx, get mobs() { return mobs; }, get mobInfo() { return mobInfo; } };
}

describe("Room.AddMob mob-info stub", () => {
  test("a prop that wanders in is recorded as a non-combatant", () => {
    const s = roomCtx();
    applyGmcpPackage(
      "Room.AddMob",
      { id: "park:goose#1", name: "an umbrella goose", hp: 5, maxHp: 5, info: { level: 1, questGiver: false, dialogue: false, aggressive: false, combatant: false } },
      s.ctx,
    );
    expect(s.mobs.map((m) => m.id)).toEqual(["park:goose#1"]);
    expect(s.mobInfo).toHaveLength(1);
    expect(s.mobInfo[0].combatant).toBe(false);
    expect(s.mobInfo[0].questAvailable).toBe(false);
  });

  test("an existing entry with per-viewer quest flags is not overwritten", () => {
    const existing: MobInfo = {
      id: "town:innkeeper", level: 5, tier: "standard", questGiver: true, questAvailable: true, questComplete: false,
      shopKeeper: false, dialogue: true, aggressive: false, combatant: false, illuminationPct: null,
    };
    const s = roomCtx([existing]);
    applyGmcpPackage(
      "Room.AddMob",
      { id: "town:innkeeper", name: "the innkeeper", hp: 50, maxHp: 50, info: { level: 5, questGiver: true, dialogue: true, aggressive: false, combatant: false } },
      s.ctx,
    );
    expect(s.mobInfo).toHaveLength(1);
    expect(s.mobInfo[0].questAvailable).toBe(true);
  });

  test("older servers without the stub add the mob and leave mob-info alone", () => {
    const s = roomCtx();
    applyGmcpPackage("Room.AddMob", { id: "zone:rat#1", name: "a rat", hp: 8, maxHp: 10 }, s.ctx);
    expect(s.mobs).toHaveLength(1);
    expect(s.mobInfo).toHaveLength(0);
  });
});
