import { describe, expect, test } from "bun:test";
import type { ComponentProps } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { InventoryPanel } from "../src/components/panels/InventoryPanel";

const baseProps: ComponentProps<typeof InventoryPanel> = {
  connected: true,
  hasCharacterProfile: true,
  inventory: [
    {
      id: "dreamweave-cowl",
      name: "a dreamweave cowl",
      keyword: "dreamweave cowl",
      slot: "head",
      consumable: false,
      useEffect: undefined,
      image: null,
    },
    {
      id: "memory-drop",
      name: "a crystallized memory drop",
      keyword: "memory drop",
      slot: null,
      consumable: true,
      useEffect: "Restores 15 HP",
      image: null,
    },
  ],
  players: [{ name: "Bramble", level: 4, sprite: null }],
  canManageItems: true,
  roomFeatures: [
    {
      id: "welcome-chest",
      name: "a shimmering welcome chest",
      keyword: "welcome chest",
      type: "container",
      state: "open",
      direction: null,
      locked: false,
      keyRequired: false,
      text: null,
    },
  ],
  containerContents: null,
  serverAssets: {},
  onWearItem: () => {},
  onDropItem: () => {},
  onGiveItem: () => {},
  onCommand: () => {},
  equipHint: false,
};

describe("inventory panel", () => {
  test("uses a shared container banner with compact Store actions", () => {
    const html = renderToStaticMarkup(
      <InventoryPanel
        {...baseProps}
        containerContents={{
          featureId: "welcome-chest",
          name: "a shimmering welcome chest",
          keyword: "welcome chest",
          items: [],
        }}
      />,
    );

    expect(html).toContain("Storing in");
    expect(html).toContain("inventory-action-put");
    expect(html).toContain('aria-label="Store a dreamweave cowl in a shimmering welcome chest"');
    expect(html).not.toContain("Put in a shimmering welcome chest");
    expect(html).toContain("container-entry-active");
  });

  test("renders an explicit Equip action for wearable items", () => {
    const html = renderToStaticMarkup(<InventoryPanel {...baseProps} />);

    expect(html).toContain("inventory-action-equip");
    expect(html).toContain('aria-label="Equip a dreamweave cowl"');
  });

  test("give action honors a registered action_give server asset", () => {
    const html = renderToStaticMarkup(
      <InventoryPanel {...baseProps} serverAssets={{ action_give: "https://cdn/give.png" }} />,
    );

    // The give button renders the server-supplied image rather than the inline SVG fallback.
    expect(html).toContain('class="inventory-action-img" src="https://cdn/give.png"');
  });

  test("wearables show stat deltas against the item worn in their slot", () => {
    const html = renderToStaticMarkup(
      <InventoryPanel
        {...baseProps}
        inventory={[{ ...baseProps.inventory[0], armor: 5, stats: { WIS: 2 } }]}
        equipment={{ head: { id: "cap", name: "a linen cap", keyword: "cap", slot: "head", armor: 2, stats: { WIS: 3, STR: 1 } } }}
      />,
    );
    expect(html).toContain("Compared with a linen cap (worn)");
    expect(html).toContain("+3 armor");
    expect(html).toContain("−1 STR");
    expect(html).toContain("−1 WIS");
    expect(html).toContain("inventory-item-delta-up");
    expect(html).toContain("inventory-item-delta-down");
  });

  test("a wearable for an empty slot says so, and an identical one reads 'same as worn'", () => {
    const empty = renderToStaticMarkup(
      <InventoryPanel {...baseProps} inventory={[{ ...baseProps.inventory[0], armor: 0 }]} equipment={{}} />,
    );
    expect(empty).toContain("slot empty");

    const same = renderToStaticMarkup(
      <InventoryPanel
        {...baseProps}
        inventory={[{ ...baseProps.inventory[0], armor: 2 }]}
        equipment={{ head: { id: "cap", name: "a linen cap", keyword: "cap", slot: "head", armor: 2 } }}
      />,
    );
    expect(same).toContain("same as worn");
  });
});
