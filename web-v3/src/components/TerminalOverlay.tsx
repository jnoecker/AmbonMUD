import { useEffect, useRef } from "react";
import type { FocusEvent, KeyboardEvent, RefObject } from "react";
import { CommandInput } from "./CommandInput";

interface TerminalOverlayProps {
  open: boolean;
  opaque: boolean;
  /** Host the live xterm element reparents into while the overlay is open. */
  hostRef: RefObject<HTMLDivElement | null>;
  inputValue: string;
  onInputChange: (value: string) => void;
  onInputKeyDown: (event: KeyboardEvent<HTMLInputElement>) => void;
  onCommand: (cmd: string) => void;
  onClose: () => void;
}

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
  inputValue,
  onInputChange,
  onInputKeyDown,
  onCommand,
  onClose,
}: TerminalOverlayProps) {
  const inputRef = useRef<HTMLInputElement | null>(null);

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

  return (
    <div
      className={`terminal-screen${open ? " terminal-screen-open" : ""}${opaque ? " terminal-screen-opaque" : ""}`}
      role="dialog"
      aria-modal={open}
      aria-label="Terminal"
      aria-hidden={!open}
      onBlur={handleFocusOut}
    >
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
      />
    </div>
  );
}
