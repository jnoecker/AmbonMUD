import { describe, expect, test } from "bun:test";
import { selectQuickPotion } from "../src/combat/quickPotion";
import type { ItemSummary } from "../src/types";

function hpPotion(name: string, heal: number, consumable = true): ItemSummary {
  return {
    id: `i:${name}`,
    name,
    keyword: name,
    slot: null,
    consumable,
    onUse: { healHp: heal, healMana: 0 },
  };
}

function manaPotion(name: string, restore: number): ItemSummary {
  return {
    id: `i:${name}`,
    name,
    keyword: name,
    slot: null,
    consumable: true,
    onUse: { healHp: 0, healMana: restore },
  };
}

describe("selectQuickPotion", () => {
  test("returns null when nothing is missing", () => {
    const pick = selectQuickPotion([hpPotion("lesser", 25)], 0, "hp");
    expect(pick).toBeNull();
  });

  test("returns null when no candidate potion exists", () => {
    const pick = selectQuickPotion([manaPotion("blue", 50)], 30, "hp");
    expect(pick).toBeNull();
  });

  test("picks the least powerful potion that fully restores the missing amount", () => {
    const inv = [hpPotion("minor", 10), hpPotion("lesser", 50), hpPotion("greater", 200)];
    const pick = selectQuickPotion(inv, 30, "hp");
    expect(pick).not.toBeNull();
    expect(pick?.item.name).toBe("lesser");
    expect(pick?.amount).toBe(50);
    expect(pick?.fullyRestores).toBe(true);
  });

  test("falls back to the most powerful potion when none fully restore", () => {
    const inv = [hpPotion("minor", 10), hpPotion("lesser", 50), hpPotion("medium", 100)];
    const pick = selectQuickPotion(inv, 190, "hp");
    expect(pick).not.toBeNull();
    expect(pick?.item.name).toBe("medium");
    expect(pick?.amount).toBe(100);
    expect(pick?.fullyRestores).toBe(false);
  });

  test("mana kind ignores HP-only potions", () => {
    const inv = [hpPotion("red", 100), manaPotion("blue", 50)];
    const pick = selectQuickPotion(inv, 40, "mana");
    expect(pick?.item.name).toBe("blue");
    expect(pick?.amount).toBe(50);
  });

  test("ignores non-consumable items even if they have a heal onUse", () => {
    const inv = [hpPotion("wand", 100, false)];
    const pick = selectQuickPotion(inv, 50, "hp");
    expect(pick).toBeNull();
  });

  test("ignores consumables without an onUse heal value", () => {
    const inv: ItemSummary[] = [
      { id: "i:scroll", name: "scroll", keyword: "scroll", slot: null, consumable: true },
    ];
    const pick = selectQuickPotion(inv, 50, "hp");
    expect(pick).toBeNull();
  });
});
