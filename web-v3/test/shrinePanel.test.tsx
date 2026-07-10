import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { ShrinePanel } from "../src/components/panels/ShrinePanel";
import type { ArcanumStatus, RoomState } from "../src/types";

const shrineRoom: RoomState = {
  id: "academy:akathavae_shrine",
  title: "The Akathavae Shrine",
  description: "",
  exits: {},
  mapX: 0,
  mapY: 0,
  shrine: true,
};

const status: ArcanumStatus = {
  pledged: false,
  rooms: 0,
  mobs: 0,
  items: 0,
  renounceCostGold: 2_500,
  repledgeAvailableAtMs: 0,
};

describe("Akathavae shrine panel", () => {
  test("renders the painted-table regions and pledge action", () => {
    const html = renderToStaticMarkup(
      <ShrinePanel
        status={status}
        room={shrineRoom}
        gold={0}
        connected
        onCommand={() => {}}
      />,
    );

    expect(html).toContain("shrine-intro");
    expect(html).toContain("shrine-covenant");
    expect(html).toContain("shrine-action");
    expect(html).toContain('aria-labelledby="shrine-covenant-heading"');
    expect(html).toContain("An invitation to illuminate");
    expect(html).toContain("Take the Pledge of the Akathavae");
    expect(html).toContain('type="button"');
  });

  test("preserves the room gate away from a shrine", () => {
    const html = renderToStaticMarkup(
      <ShrinePanel
        status={status}
        room={{ ...shrineRoom, id: "academy:courtyard", shrine: false }}
        gold={0}
        connected
        onCommand={() => {}}
      />,
    );

    expect(html).toContain("Look for the ✨ mark on your map");
    expect(html).not.toContain("Take the Pledge of the Akathavae</button>");
  });

  test("shows the pledged covenant and server-provided renounce cost", () => {
    const html = renderToStaticMarkup(
      <ShrinePanel
        status={{ ...status, pledged: true }}
        room={shrineRoom}
        gold={3_000}
        connected
        onCommand={() => {}}
      />,
    );

    expect(html).toContain("Your vow endures");
    expect(html).toContain("Renouncing costs 2,500 gold");
    expect(html).toContain("Renounce the Vow");
    expect(html).toContain('aria-label="Renounce the Akathavae vow"');
  });
});
