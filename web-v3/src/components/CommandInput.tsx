import { useRef } from "react";
import type { FormEvent, KeyboardEvent } from "react";

interface CommandInputProps {
  inputValue: string;
  onInputChange: (value: string) => void;
  onInputKeyDown?: (event: KeyboardEvent<HTMLInputElement>) => void;
  onCommand: (cmd: string) => void;
}

/**
 * Slim command input overlaid on the bottom of the canvas. Replaces the input
 * that lived in the now-removed bottom action bar.
 */
export function CommandInput({ inputValue, onInputChange, onInputKeyDown, onCommand }: CommandInputProps) {
  const inputRef = useRef<HTMLInputElement | null>(null);

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
        placeholder="Type a command..."
        value={inputValue}
        onChange={(e) => onInputChange(e.target.value)}
        onKeyDown={onInputKeyDown}
      />
      <button type="submit" className="canvas-command-send" aria-label="Send">
        <svg viewBox="0 0 24 24" className="canvas-command-send-icon" fill="currentColor">
          <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
        </svg>
      </button>
    </form>
  );
}
