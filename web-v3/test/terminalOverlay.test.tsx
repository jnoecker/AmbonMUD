import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { KioskBar } from "../src/components/KioskBar";
import { TerminalOverlay } from "../src/components/TerminalOverlay";

describe("kiosk bar", () => {
  test("no longer offers a terminal kiosk — the overlay owns that entry point", () => {
    const html = renderToStaticMarkup(
      <KioskBar serverAssets={{}} activePopout={null} onOpenPanel={() => {}} />,
    );
    expect(html).toContain("Character");
    expect(html).toContain("Combat Log");
    expect(html).not.toContain("Terminal");
  });
});

describe("terminal overlay", () => {
  const baseProps = {
    opaque: false,
    hostRef: { current: null },
    inputValue: "",
    onInputChange: () => {},
    onInputKeyDown: () => {},
    onCommand: () => {},
    onClose: () => {},
  };

  test("stays mounted but hidden when closed, so the log host survives", () => {
    const html = renderToStaticMarkup(<TerminalOverlay {...baseProps} open={false} />);
    expect(html).toContain("terminal-screen");
    expect(html).not.toContain("terminal-screen-open");
    expect(html).toContain('aria-hidden="true"');
    expect(html).toContain("terminal-screen-host");
  });

  test("opens translucent first, then opaque once the player types", () => {
    const open = renderToStaticMarkup(<TerminalOverlay {...baseProps} open={true} />);
    expect(open).toContain("terminal-screen-open");
    expect(open).not.toContain("terminal-screen-opaque");

    const opaque = renderToStaticMarkup(
      <TerminalOverlay {...baseProps} open={true} opaque={true} />,
    );
    expect(opaque).toContain("terminal-screen-opaque");
  });
});
