import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { Drawer } from "../src/components/Drawer";
import { WorldFeaturesPopout } from "../src/components/WorldFeaturesPopout";
import type { RoomFeature } from "../src/types";

const sign: RoomFeature = {
  id: "welcome-card",
  name: "a tea-stained welcome card",
  keyword: "card",
  type: "sign",
  state: null,
  direction: null,
  locked: null,
  keyRequired: null,
  text: "Dearest newcomer — a few first words, with love.",
  backgroundImage: "https://art.example/welcome-card.webp",
  plateImage: null,
  handleImage: null,
  leverPivot: null,
  upAngle: null,
  downAngle: null,
  frameImage: null,
  leafImage: null,
  hinge: null,
  openAngle: null,
  leafScale: null,
  leafOffsetY: null,
  keyImage: null,
  keyName: null,
};

describe("sign readability", () => {
  test("marks art-backed feature content and its drawer as skinned", () => {
    const panelHtml = renderToStaticMarkup(
      <WorldFeaturesPopout
        roomFeatures={[sign]}
        containerContents={null}
        preferredType="sign"
        serverAssets={{}}
        onCommand={() => {}}
      />,
    );
    const drawerHtml = renderToStaticMarkup(
      <Drawer
        open
        title={sign.name}
        onClose={() => {}}
        variant="feature"
        skinBg={sign.backgroundImage ?? undefined}
      >
        {panelHtml}
      </Drawer>,
    );

    expect(panelHtml).toContain("feat-panel feat-sign has-art");
    expect(drawerHtml).toContain("drawer-sheet drawer-sheet-feature drawer-sheet-skinned");
  });

  test("keeps the CSS-only fallback free of the art veil", () => {
    const html = renderToStaticMarkup(
      <WorldFeaturesPopout
        roomFeatures={[{ ...sign, backgroundImage: null }]}
        containerContents={null}
        preferredType="sign"
        serverAssets={{}}
        onCommand={() => {}}
      />,
    );

    expect(html).toContain("feat-panel feat-sign");
    expect(html).not.toContain("feat-sign has-art");
    expect(html).toContain("feat-fallback-sign");
  });

  test("defines localized reading veils for sign copy and skinned titles", async () => {
    const styles = await Bun.file(new URL("../src/styles.css", import.meta.url)).text();

    expect(styles).toContain("--surface-reading-veil:");
    expect(styles).toContain("--shadow-reading-text:");
    expect(styles).toContain(".feat-sign.has-art .feat-sign-text::before");
    expect(styles).toContain(".drawer-sheet-feature.drawer-sheet-skinned .drawer-title::before");
  });
});
