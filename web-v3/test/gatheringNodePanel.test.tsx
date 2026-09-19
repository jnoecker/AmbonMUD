import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { GatheringNodePanel } from "../src/components/panels/GatheringNodePanel";
import { applyGmcpPackage } from "../src/gmcp/applyGmcpPackage";
import type { CraftingNode, CraftingSkill } from "../src/types";

const node: CraftingNode = {
  id: "mine:copper_vein",
  name: "a copper ore vein",
  skill: "mining",
  skillRequired: 3,
  image: null,
  yields: [{ itemId: "mine:copper_ore", name: "copper ore", image: null, minQuantity: 1, maxQuantity: 3 }],
  rareYields: [{ itemId: "mine:silver_nugget", name: "a silver nugget", image: null, quantity: 1, chancePct: 15 }],
  respawnSeconds: 90,
  xpReward: 10,
  respawnAtMs: null,
};

const mining: CraftingSkill = { id: "mining", name: "Mining", level: 5, xp: 0, xpToNext: 10, maxLevel: 100, type: "gathering" };

const base = {
  node,
  skills: [mining],
  gatherCooldownUntilMs: 0,
  serverAssets: {},
  onClose: () => {},
  onCommand: () => {},
  onZoomImage: () => {},
};

describe("gathering node card", () => {
  test("lists yields with quantity ranges and rare odds, and offers Gather", () => {
    const html = renderToStaticMarkup(<GatheringNodePanel {...base} />);
    expect(html).toContain("a copper ore vein");
    expect(html).toContain("copper ore");
    expect(html).toContain("×1–3");
    expect(html).toContain("a silver nugget");
    expect(html).toContain("15%");
    expect(html).toContain("Mining 3");
    expect(html).toContain("yours 5");
    expect(html).toContain("1m 30s");
    expect(html).not.toContain("disabled");
  });

  test("a too-low skill disables Gather and says what's needed", () => {
    const html = renderToStaticMarkup(<GatheringNodePanel {...base} skills={[{ ...mining, level: 1 }]} />);
    expect(html).toContain("Needs Mining 3 (you have 1)");
    expect(html).toContain("disabled");
  });

  test("a depleted node shows the regrow countdown", () => {
    const html = renderToStaticMarkup(
      <GatheringNodePanel {...base} node={{ ...node, respawnAtMs: Date.now() + 42_000 }} />,
    );
    expect(html).toMatch(/regrows in 4[12]s/);
    expect(html).toContain("depleted");
  });
});

describe("Crafting.Nodes parsing", () => {
  test("yields, rare odds and the respawn timer come through; older servers default to empty", () => {
    let nodes: CraftingNode[] = [];
    const ctx = {
      setCraftingNodes: (next: CraftingNode[] | ((prev: CraftingNode[]) => CraftingNode[])) => {
        nodes = typeof next === "function" ? next(nodes) : next;
      },
    } as unknown as Parameters<typeof applyGmcpPackage>[2];
    applyGmcpPackage(
      "Crafting.Nodes",
      [
        {
          id: "a", name: "vein", skill: "mining", skillRequired: 1,
          yields: [{ itemId: "x", name: "ore", minQuantity: 1, maxQuantity: 2 }],
          rareYields: [{ itemId: "y", name: "gem", quantity: 1, chancePct: 5 }],
          respawnSeconds: 60, xpReward: 8, respawnRemainingMs: 30_000,
        },
        { id: "b", name: "bush", skill: "herbalism", skillRequired: 1 },
      ],
      ctx,
    );
    expect(nodes[0].yields[0].maxQuantity).toBe(2);
    expect(nodes[0].rareYields[0].chancePct).toBe(5);
    expect(nodes[0].respawnAtMs).toBeGreaterThan(Date.now());
    expect(nodes[1].yields).toEqual([]);
    expect(nodes[1].respawnAtMs).toBeNull();
  });
});
