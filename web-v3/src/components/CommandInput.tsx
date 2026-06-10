import { useRef } from "react";
import type { FormEvent, KeyboardEvent, RefObject } from "react";

interface CommandInputProps {
  inputValue: string;
  onInputChange: (value: string) => void;
  onInputKeyDown?: (event: KeyboardEvent<HTMLInputElement>) => void;
  onCommand: (cmd: string) => void;
  /** Lets the parent focus the input (e.g. when the terminal overlay opens). */
  inputRef?: RefObject<HTMLInputElement | null>;
  placeholder?: string;
  /** Painted art for the send button (e.g. the quill); falls back to the SVG. */
  sendIconUrl?: string | null;
}

/**
 * Slim command input overlaid on the bottom of the canvas. Replaces the input
 * that lived in the now-removed bottom action bar.
 */
export function CommandInput({
  inputValue,
  onInputChange,
  onInputKeyDown,
  onCommand,
  inputRef: externalRef,
  placeholder = "Type a command...",
  sendIconUrl = null,
}: CommandInputProps) {
  const internalRef = useRef<HTMLInputElement | null>(null);
  const inputRef = externalRef ?? internalRef;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const cmd = inputValue.trim();
    if (!cmd) return;
    onCommand(cmd);
    onInputChange("");
  };

  return (
    <form className="canvas-command" onSubmit={submit}>
      <input
        ref={inputRef}
        type="text"
        className="canvas-command-input"
        placeholder={placeholder}
        value={inputValue}
        onChange={(e) => onInputChange(e.target.value)}
        onKeyDown={onInputKeyDown}
      />
      <button
        type="submit"
        className={`canvas-command-send${sendIconUrl ? " canvas-command-send-skinned" : ""}`}
        aria-label="Send"
      >
        {sendIconUrl ? (
          <img src={sendIconUrl} alt="" className="canvas-command-send-img" />
        ) : (
          <svg viewBox="0 0 24 24" className="canvas-command-send-icon" fill="currentColor">
            <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
          </svg>
        )}
      </button>
    </form>
  );
}
