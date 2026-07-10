import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { ArcanumPanel } from "../src/components/panels/ArcanumPanel";
import type { ArcanumJournal } from "../src/types";

const journal: ArcanumJournal = {
  pledged: true,
  zones: [
    {
      zone: "auringold_academy",
      roomsRecorded: 30,
      roomsTotal: 67,
      mobsRecorded: 20,
      mobsTotal: 93,
      itemsRecorded: 5,
      itemsTotal: 38,
    },
  ],
  mobs: [
    {
      key: "academy:jackalope",
      name: "a fanged jackalope",
      image: "/images/jackalope.png",
      timesRecorded: 2,
      firstRecordedAtMs: 100,
      source: "illuminated",
      firstBy: "Ambuoroko",
      firstAtMs: 100,
      firstSlainBy: "Yara11",
      firstSlainAtMs: 200,
    },
  ],
  items: [],
  rooms: [],
};

describe("Arcanum panel", () => {
  test("renders the painted-folio regions with player-facing zone names", () => {
    const html = renderToStaticMarkup(
      <ArcanumPanel
        journal={journal}
        status={null}
        playerName="Ambuoroko"
        connected
        onCommand={() => {}}
      />,
    );

    expect(html).toContain("Living field folio");
    expect(html).toContain("Journey ledger");
    expect(html).toContain("Auringold Academy");
    expect(html).not.toContain("auringold_academy");
    expect(html).toContain('aria-label="Auringold Academy completion"');
    expect(html).toContain('decoding="async"');
  });

  test("exposes a labelled search and complete tab relationships", () => {
    const html = renderToStaticMarkup(
      <ArcanumPanel
        journal={journal}
        status={null}
        playerName="Ambuoroko"
        connected
        onCommand={() => {}}
      />,
    );

    expect(html).toContain('<label class="sr-only" for="arcanum-search">Search the Arcanum</label>');
    expect(html).toContain('id="arcanum-tab-mobs"');
    expect(html).toContain('aria-controls="arcanum-panel-mobs"');
    expect(html).toContain('id="arcanum-panel-mobs"');
    expect(html).toContain('aria-labelledby="arcanum-tab-mobs"');
    expect(html).toContain('tabindex="0"');
    expect(html).toContain('tabindex="-1"');
  });

  test("announces the folio loading state", () => {
    const html = renderToStaticMarkup(
      <ArcanumPanel
        journal={null}
        status={null}
        playerName="Ambuoroko"
        connected={false}
        onCommand={() => {}}
      />,
    );

    expect(html).toContain('role="status"');
    expect(html).toContain('aria-live="polite"');
    expect(html).toContain("Opening your field folio");
  });
});
