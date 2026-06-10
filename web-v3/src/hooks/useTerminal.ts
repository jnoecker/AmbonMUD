import { useCallback, useEffect, useRef, useState } from "react";
import type { RefObject } from "react";
import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import "@xterm/xterm/css/xterm.css";

interface TerminalHosts {
  /** Off-screen stash the xterm element lives in while the GUI is in charge. */
  hiddenHostRef: RefObject<HTMLDivElement | null>;
  /** Overlay host the element reparents into while the overlay is open. */
  overlayHostRef: RefObject<HTMLDivElement | null>;
}

// Both themes keep the cell background transparent (allowTransparency) so the
// scroll's CSS background — dark glass or the parchment art — shows through.
const DARK_THEME = {
  background: "rgba(0, 0, 0, 0)",
  foreground: "#d8dcef",
  cursor: "#b9aed8",
  selectionBackground: "rgba(185, 174, 216, 0.34)",
};

/** Ink-on-parchment: dark foreground + the 16 ANSI colors remapped to inks
 *  that stay legible on light paper (server text assumes a dark terminal). */
const INK_THEME = {
  background: "rgba(0, 0, 0, 0)",
  foreground: "#2e2212",
  cursor: "#2e2212",
  cursorAccent: "#f3ead2",
  selectionBackground: "rgba(106, 74, 36, 0.3)",
  black: "#2e2212",
  red: "#8c2f28",
  green: "#3f6d2e",
  yellow: "#8a6414",
  blue: "#2f4d8a",
  magenta: "#7a3a78",
  cyan: "#1f6b6b",
  white: "#5a4a30",
  brightBlack: "#6a5a40",
  brightRed: "#a83a30",
  brightGreen: "#4d8438",
  brightYellow: "#a07818",
  brightBlue: "#3a5fa8",
  brightMagenta: "#94508f",
  brightCyan: "#287f7f",
  brightWhite: "#2e2212",
};

/**
 * Owns a single xterm.js instance that lives for the whole session and
 * accumulates every non-GMCP byte the server sends — the same ANSI stream a
 * telnet user sees. While the GUI is in charge the terminal sits in a hidden
 * off-screen host (`.terminal-hidden`) so the log keeps growing silently;
 * opening the overlay reparents the existing element, scrollback intact.
 *
 * The host refs stay in App (and are attached to JSX there) so this hook's
 * return value carries no refs — the react-hooks/refs lint rule forbids
 * accessing hook-returned ref carriers during render.
 */
export function useTerminal({ hiddenHostRef, overlayHostRef }: TerminalHosts) {
  const terminalRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const [open, setOpen] = useState(false);
  // Translucent when first summoned (game stays visible behind the log);
  // turns opaque once the user starts typing and commits to terminal mode.
  const [opaque, setOpaque] = useState(false);

  const fit = useCallback(() => {
    const term = terminalRef.current;
    const fitAddon = fitAddonRef.current;
    // Fit to whichever container the terminal is currently in
    const host = overlayHostRef.current ?? hiddenHostRef.current;
    if (!term || !fitAddon || !host) return;
    if (host.clientWidth <= 0 || host.clientHeight <= 0) return;

    const width = host.clientWidth;
    const nextFontSize = width < 560 ? 12 : width < 760 ? 13 : 14;
    if (term.options.fontSize !== nextFontSize) {
      term.options.fontSize = nextFontSize;
    }

    fitAddon.fit();
  }, [hiddenHostRef, overlayHostRef]);

  useEffect(() => {
    if (!hiddenHostRef.current) return;

    const term = new Terminal({
      cursorBlink: false,
      disableStdin: true,
      fontFamily: '"JetBrains Mono", "Cascadia Mono", monospace',
      fontSize: 14,
      rows: 30,
      scrollback: 5000,
      convertEol: false,
      allowTransparency: true,
      theme: DARK_THEME,
    });

    const fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    term.open(hiddenHostRef.current);

    terminalRef.current = term;
    fitAddonRef.current = fitAddon;

    return () => {
      term.dispose();
      fitAddonRef.current = null;
      terminalRef.current = null;
    };
  }, [hiddenHostRef]);

  // Refit on window resize and once fonts settle (mono metrics change cols).
  useEffect(() => {
    const onResize = () => fit();
    window.addEventListener("resize", onResize);
    const fontSet = document.fonts;
    let cancelled = false;
    const refit = () => {
      if (!cancelled) fit();
    };
    fontSet?.ready.then(refit).catch(() => undefined);
    fontSet?.addEventListener("loadingdone", refit);
    return () => {
      cancelled = true;
      window.removeEventListener("resize", onResize);
      fontSet?.removeEventListener("loadingdone", refit);
    };
  }, [fit]);

  // Reparent the live terminal element into the overlay when open, back to
  // the hidden host when closed — the instance (and its scrollback) survives.
  useEffect(() => {
    const term = terminalRef.current;
    const termEl = term?.element;
    if (!term || !termEl) return;

    if (open && overlayHostRef.current) {
      overlayHostRef.current.appendChild(termEl);
      window.requestAnimationFrame(() => {
        fit();
        term.scrollToBottom();
      });
      const delayedFit = window.setTimeout(() => {
        fit();
        term.scrollToBottom();
      }, 80);
      return () => window.clearTimeout(delayedFit);
    } else if (hiddenHostRef.current && termEl.parentElement !== hiddenHostRef.current) {
      hiddenHostRef.current.appendChild(termEl);
    }
  }, [open, fit, hiddenHostRef, overlayHostRef]);

  /** Raw server output — ANSI escapes pass straight through to xterm. */
  const write = useCallback((text: string) => {
    terminalRef.current?.write(text);
  }, []);

  /** Local echo of a command the player sent, like a telnet client shows. */
  const echoCommand = useCallback((command: string) => {
    terminalRef.current?.write(`${command}\r\n`);
  }, []);

  /** Dim client-side status line (connection lost, reconnecting, ...). */
  const writeSystem = useCallback((message: string) => {
    terminalRef.current?.write(`\r\n\x1b[2m${message}\x1b[0m\r\n`);
  }, []);

  /** xterm tracks its own selection (not window.getSelection). */
  const hasSelection = useCallback(() => terminalRef.current?.hasSelection() ?? false, []);

  /** Dark ink on parchment when the scroll art is present; light-on-dark otherwise. */
  const setInkTheme = useCallback((enabled: boolean) => {
    const term = terminalRef.current;
    if (term) term.options.theme = enabled ? INK_THEME : DARK_THEME;
  }, []);

  const openTerminal = useCallback(() => setOpen(true), []);

  const closeTerminal = useCallback(() => {
    setOpen(false);
    setOpaque(false);
  }, []);

  return {
    open,
    opaque,
    setOpaque,
    openTerminal,
    closeTerminal,
    write,
    echoCommand,
    writeSystem,
    hasSelection,
    setInkTheme,
  };
}
