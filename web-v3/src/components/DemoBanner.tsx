import { useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";

interface DemoBannerProps {
  /**
   * Auto-prompts the user with the claim modal as soon as it becomes true.
   * Driven by App.tsx when the demo character first hits level 2.
   */
  autoOpen?: boolean;
  /** Submits `claim [name] <password>` over the line interface. */
  onClaim: (line: string) => void;
}

/**
 * Persistent topbar banner shown while the character is an unclaimed demo.
 * Click "Save Progress" to open a small modal collecting an optional new
 * name and a required password, then sends a `claim` command.
 */
export function DemoBanner({ autoOpen = false, onClaim }: DemoBannerProps) {
  const [manualOpen, setManualOpen] = useState(false);
  const [dismissedAuto, setDismissedAuto] = useState(false);
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  // Render-time derivation: auto-opens once when autoOpen flips true, and stays
  // closed if the user dismisses it. Manual reopening is independent.
  const open = manualOpen || (autoOpen && !dismissedAuto);

  const close = useCallback(() => {
    setManualOpen(false);
    setDismissedAuto(true);
    setError(null);
  }, []);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  const handleSubmit = useCallback((e: FormEvent) => {
    e.preventDefault();
    const pw = password.trim();
    if (pw.length === 0) {
      setError("Password is required.");
      return;
    }
    const trimmedName = name.trim();
    const line = trimmedName.length > 0 ? `claim ${trimmedName} ${pw}` : `claim ${pw}`;
    onClaim(line);
    setPassword("");
    setError(null);
    setManualOpen(false);
    setDismissedAuto(true);
  }, [name, password, onClaim]);

  return (
    <>
      <div className="demo-banner" role="status">
        <span className="demo-banner-label">
          <span className="demo-banner-tag">Demo</span>
          You&rsquo;re playing as a guest &mdash; progress isn&rsquo;t saved.
        </span>
        <button
          type="button"
          className="demo-banner-button"
          onClick={() => setManualOpen(true)}
        >
          Save Progress
        </button>
      </div>

      {open && (
        <div className="login-modal-backdrop" onClick={close}>
          <div
            className="login-modal demo-claim-modal"
            role="dialog"
            aria-modal="true"
            aria-label="Save your demo character"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="login-modal-title">Save Your Character</h2>
            <p className="login-step-sub">
              Pick a password so you can log back in. You can also rename your character if you like.
            </p>
            {error && <p className="login-error">{error}</p>}
            <form onSubmit={handleSubmit} className="login-form demo-claim-form">
              <label className="demo-claim-field">
                <span className="demo-claim-field-label">New name (optional)</span>
                <input
                  ref={inputRef}
                  className="login-input"
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Leave blank to keep current name"
                  autoComplete="off"
                  spellCheck={false}
                  maxLength={16}
                />
              </label>
              <label className="demo-claim-field">
                <span className="demo-claim-field-label">Password</span>
                <input
                  className="login-input"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Choose a password"
                  autoComplete="new-password"
                  required
                />
              </label>
              <div className="login-choice-row">
                <button type="submit" className="login-button">
                  Save Character
                </button>
                <button
                  type="button"
                  className="login-button login-button-secondary"
                  onClick={close}
                >
                  Not now
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  );
}
