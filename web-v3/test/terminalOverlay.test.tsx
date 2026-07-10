import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { KioskBar } from "../src/components/KioskBar";
import { TerminalOverlay } from "../src/components/TerminalOverlay";
import { shouldLoadTerminalBundle } from "../src/hooks/useTerminal";

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
    hasSelection: () => false,
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

  test("applies the parchment skin and quill only when the art is present", () => {
    const unskinned = renderToStaticMarkup(<TerminalOverlay {...baseProps} open={true} />);
    expect(unskinned).not.toContain("terminal-screen-skinned");
    expect(unskinned).not.toContain("canvas-command-send-skinned");

    const skinned = renderToStaticMarkup(
      <TerminalOverlay
        {...baseProps}
        open={true}
        parchmentBg="https://art.example/parchment.png"
        quillUrl="https://art.example/quill.png"
      />,
    );
    expect(skinned).toContain("terminal-screen-skinned");
    expect(skinned).toContain("--terminal-parchment");
    expect(skinned).toContain("canvas-command-send-skinned");
    expect(skinned).toContain("Inscribe a command");
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

  test("keeps xterm off the login path until UI or accessibility needs it", () => {
    expect(shouldLoadTerminalBundle(false, false)).toBe(false);
    expect(shouldLoadTerminalBundle(true, false)).toBe(true);
    expect(shouldLoadTerminalBundle(false, true)).toBe(true);
  });
});
