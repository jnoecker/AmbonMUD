import { useEffect, useRef } from "react";
import type { FocusEvent, KeyboardEvent, PointerEvent as ReactPointerEvent, RefObject } from "react";
import { CommandInput } from "./CommandInput";

interface TerminalOverlayProps {
  open: boolean;
  opaque: boolean;
  /** Host the live xterm element reparents into while the overlay is open. */
  hostRef: RefObject<HTMLDivElement | null>;
  /** Whether the xterm has an active text selection (its own model, not window.getSelection). */
  hasSelection: () => boolean;
  /** Pressed-flower parchment art layered behind the opaque log (ART_CONTRACT). */
  parchmentBg?: string | null;
  /** Quill art for the send button; falls back to the SVG. */
  quillUrl?: string | null;
  inputValue: string;
  onInputChange: (value: string) => void;
  onInputKeyDown: (event: KeyboardEvent<HTMLInputElement>) => void;
  onCommand: (cmd: string) => void;
  onClose: () => void;
}

/** Pointer travel beyond this is a drag (text selection / scroll), not a click. */
const CLICK_SLOP_PX = 8;
/** Holds longer than this are long-presses (touch selection), not clicks. */
const CLICK_MAX_MS = 500;

/**
 * Full-screen terminal over the GUI. Summoned by focusing the dock input
 * (desktop) or the services-stack button (small screens); the session-long
 * xterm log slides in with its scrollback intact. Translucent until the
 * player types, then opaque. Esc or the close button returns to the GUI.
 */
export function TerminalOverlay({
  open,
  opaque,
  hostRef,
  hasSelection,
  parchmentBg = null,
  quillUrl = null,
  inputValue,
  onInputChange,
  onInputKeyDown,
  onCommand,
  onClose,
}: TerminalOverlayProps) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const pointerDownRef = useRef<{ x: number; y: number; at: number; closeEligible: boolean } | null>(null);

  useEffect(() => {
    if (!open) return;
    window.requestAnimationFrame(() => inputRef.current?.focus());
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // If focus escapes the overlay entirely (e.g. tabbing out), dismiss it —
  // the "click away and you're back in the GUI" affordance.
  const handleFocusOut = (event: FocusEvent<HTMLDivElement>) => {
    if (!open) return;
    const next = event.relatedTarget as Node | null;
    if (next && !event.currentTarget.contains(next)) onClose();
  };

  // A plain click anywhere in the log dismisses the overlay; click-drag
  // (selecting text to copy), long-press, and clicks on the input row /
  // close button / scrollbar do not.
  const handlePointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    const onChrome = !!target.closest(".canvas-command, .terminal-screen-close");
    // Clicks on the xterm viewport's native scrollbar land outside clientWidth.
    const viewport = target.closest(".xterm-viewport") as HTMLElement | null;
    const onScrollbar = !!viewport
      && event.clientX >= viewport.getBoundingClientRect().left + viewport.clientWidth;
    pointerDownRef.current = {
      x: event.clientX,
      y: event.clientY,
      at: performance.now(),
      closeEligible: !onChrome && !onScrollbar,
    };
  };

  const handlePointerUp = (event: ReactPointerEvent<HTMLDivElement>) => {
    const down = pointerDownRef.current;
    pointerDownRef.current = null;
    if (!open || !down || !down.closeEligible) return;
    if (Math.hypot(event.clientX - down.x, event.clientY - down.y) > CLICK_SLOP_PX) return;
    if (performance.now() - down.at > CLICK_MAX_MS) return;
    if (hasSelection()) return;
    onClose();
  };

  // Parchment art arrives via Server.Assets; the unskinned flat panel is the
  // CSS fallback when the key is absent (ART_CONTRACT fallback hierarchy).
  const skinVars: Record<string, string> = {};
  if (parchmentBg) skinVars["--terminal-parchment"] = `url("${parchmentBg}")`;

  return (
    <div
      className={`terminal-screen${open ? " terminal-screen-open" : ""}${opaque ? " terminal-screen-opaque" : ""}${parchmentBg ? " terminal-screen-skinned" : ""}`}
      role="dialog"
      aria-modal={open}
      aria-label="Terminal"
      aria-hidden={!open}
      style={Object.keys(skinVars).length > 0 ? skinVars : undefined}
      onBlur={handleFocusOut}
      onPointerDown={handlePointerDown}
      onPointerUp={handlePointerUp}
    >
      {/* The scroll — a narrow column matching the dock input that unrolls
          upward. With parchment art it IS the parchment; otherwise dark glass. */}
      <div className="terminal-scroll">
        <div className="terminal-screen-bar">
          <span className="terminal-screen-title">Terminal</span>
          <button
            type="button"
            className="terminal-screen-close"
            onClick={onClose}
            aria-label="Close terminal"
            tabIndex={open ? 0 : -1}
          >
            ×
          </button>
        </div>
        <div ref={hostRef} className="terminal-screen-host" />
        <CommandInput
          inputRef={inputRef}
          inputValue={inputValue}
          onInputChange={onInputChange}
          onInputKeyDown={onInputKeyDown}
          onCommand={onCommand}
          placeholder="Inscribe a command..."
          sendIconUrl={quillUrl}
        />
      </div>
    </div>
  );
}
