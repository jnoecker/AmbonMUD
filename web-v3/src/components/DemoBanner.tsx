import { useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { ArtScene } from "../canvas/ArtScene";
import { useOrientedArt } from "../canvas/loginArtFit";

interface DemoBannerProps {
  /**
   * Auto-prompts the user with the claim modal as soon as it becomes true.
   * Driven by App.tsx when the demo character first hits level 2.
   */
  autoOpen?: boolean;
  /**
   * External open requests (the canvas Claim button). Each increment opens
   * the modal, even after the auto-open was dismissed.
   */
  openRequestId?: number;
  /** Painted Save Your Character scene (`login_claim_bg`); null → CSS dialog. */
  backgroundImage: string | null;
  /** Phone-portrait companion (`login_claim_bg_portrait`); preferred on portrait viewports. */
  backgroundImagePortrait: string | null;
  /** Submits `claim [name] <password>` over the line interface. */
  onClaim: (line: string) => void;
}

/**
 * Persistent topbar banner shown while the character is an unclaimed demo.
 * Click "Save Progress" to open a modal collecting an optional new name and
 * a required password, then sends a `claim` command. When the painted claim
 * scene is available the modal renders as a full-screen art window.
 */
export function DemoBanner({ autoOpen = false, openRequestId = 0, backgroundImage, backgroundImagePortrait, onClaim }: DemoBannerProps) {
  const [manualOpen, setManualOpen] = useState(false);
  const [dismissedAuto, setDismissedAuto] = useState(false);
  const [handledRequestId, setHandledRequestId] = useState(0);
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  // Orientation-aware art pick, gated on the image actually loading —
  // failed art falls back to the CSS dialog.
  const { url: art, phone } = useOrientedArt(backgroundImage, backgroundImagePortrait);

  // Render-time derivation: auto-opens once when autoOpen flips true, and stays
  // closed if the user dismisses it. Manual reopening (banner button or the
  // canvas Claim button) is independent.
  if (openRequestId > handledRequestId) {
    setHandledRequestId(openRequestId);
    setManualOpen(true);
  }
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

  const artOpen = open && art !== null;

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

      {artOpen && art && (
        <ArtScene
          url={art}
          stageClass={`${phone ? "login-art-stage--phone" : "login-art-stage--book"} login-art-stage--claim`}
          label="Save your demo character"
        >
          <h1 className="sr-only">Save your character — pick a password, optionally rename</h1>
          <form onSubmit={handleSubmit} className="login-art-form">
            <input
              ref={inputRef}
              className="login-art-input-dark lcm-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Leave blank to keep current name"
              autoComplete="off"
              spellCheck={false}
              maxLength={16}
              aria-label="New name (optional)"
            />
            <input
              className="login-art-input-dark lcm-password"
              type={showPassword ? "text" : "password"}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Choose a password"
              autoComplete="new-password"
              required
              aria-label="Password"
            />
            <button
              type="button"
              className="login-art-hotspot login-art-wand lcm-toggle"
              onClick={() => setShowPassword((v) => !v)}
              aria-pressed={showPassword}
              title={showPassword ? "Hide password" : "Show password"}
              aria-label={showPassword ? "Hide password" : "Show password"}
            />
            <button type="submit" className="login-art-hotspot lcm-save" title="Save Character" aria-label="Save character" />
            <button
              type="button"
              className="login-art-hotspot lcm-notnow"
              onClick={close}
              title="Not now"
              aria-label="Not now"
            />
          </form>
          {error && <p className="login-art-error-chip login-art-error-chip--dark lcm-error" role="alert">{error}</p>}
        </ArtScene>
      )}

      {open && !artOpen && (
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
