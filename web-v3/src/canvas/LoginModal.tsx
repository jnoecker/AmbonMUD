import { useCallback, useEffect, useRef, useState } from "react";
import type { CSSProperties, FormEvent } from "react";
import type { LoginClassOption, LoginErrorState, LoginPromptState, LoginRaceOption } from "../types";
import { ArtScene } from "./ArtScene";
import { ART_FIT_PORTRAIT, useArtImage, useArtImageState, useMediaFits, useOrientedArt } from "./loginArtFit";

interface LoginModalProps {
  loginPrompt: LoginPromptState;
  loginError: LoginErrorState | null;
  /** Painted login scene art (`login_*_bg` from Server.Assets); missing keys → CSS UI. */
  serverAssets: Record<string, string>;
  onSubmit: (value: string) => void;
}

export function LoginModal({ loginPrompt, loginError, serverAssets, onSubmit }: LoginModalProps) {
  const [inputValue, setInputValue] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [prevState, setPrevState] = useState(loginPrompt.state);
  const inputRef = useRef<HTMLInputElement | null>(null);

  // Clear input when login step changes
  if (loginPrompt.state !== prevState) {
    setPrevState(loginPrompt.state);
    setInputValue("");
    setShowPassword(false);
  }

  const handleSubmit = useCallback((e: FormEvent) => {
    e.preventDefault();
    const val = inputValue.trim();
    if (val.length === 0) return;
    onSubmit(val);
    setInputValue("");
  }, [inputValue, onSubmit]);

  const handleChoice = useCallback((value: string) => {
    onSubmit(value);
  }, [onSubmit]);

  const artFitsPortrait = useMediaFits(ART_FIT_PORTRAIT);

  const errorForState = loginError?.state === loginPrompt.state ? loginError.message : null;

  const isWide = loginPrompt.state === "raceSelection" || loginPrompt.state === "classSelection";

  // One painted scene per login step. The landscape scenes carry a
  // phone-portrait companion (chosen by viewport orientation); race/class are
  // slot grids that are already portrait and use their own stage.
  const SCENE_ART: Record<string, { key: string; portraitKey?: string; slots?: boolean }> = {
    name: { key: "login_bg", portraitKey: "login_bg_portrait" },
    password: { key: "login_password_bg", portraitKey: "login_password_bg_portrait" },
    newPassword: { key: "login_set_password_bg", portraitKey: "login_set_password_bg_portrait" },
    confirmCreate: { key: "login_confirm_bg", portraitKey: "login_confirm_bg_portrait" },
    raceSelection: { key: "login_race_bg", slots: true },
    classSelection: { key: "login_class_bg", slots: true },
  };
  const scene = SCENE_ART[loginPrompt.state];
  const isSlotScene = scene?.slots === true;
  // Gated on the image actually loading: a URL that 404s or is blocked
  // client-side must fall back to the CSS UI, not render a black scene.
  const slotArtState = useArtImageState(scene && isSlotScene && artFitsPortrait ? serverAssets[scene.key] ?? null : null);
  const oriented = useOrientedArt(
    scene && !isSlotScene ? serverAssets[scene.key] ?? null : null,
    scene && !isSlotScene && scene.portraitKey ? serverAssets[scene.portraitKey] ?? null : null,
  );
  const sceneArt = isSlotScene ? slotArtState.url : oriented.url;
  /** True while the step's art is still loading — hold a dark frame, don't flash the CSS dialog. */
  const scenePending = isSlotScene ? slotArtState.pending : oriented.pending;
  /** True when the phone-portrait companion (941×1672 stage) is active. */
  const phoneScene = !isSlotScene && oriented.phone;

  // Refocus when the step changes, and again when the art settles — while the
  // holding frame (or a late-arriving painted scene swap) is up, the input the
  // step-change pass tried to focus doesn't exist yet.
  useEffect(() => {
    inputRef.current?.focus();
  }, [loginPrompt.state, sceneArt, scenePending]);

  // Decorative blurred backdrop behind the fallback modal steps.
  const backdropArt = useArtImage(serverAssets["login_bg"] ?? null);
  const artVars = backdropArt
    ? ({ "--login-art": `url("${backdropArt}")` } as CSSProperties)
    : undefined;

  const stepDialogLabel: Record<string, string> = {
    password: "Sign in",
    confirmCreate: "Create a new character?",
    newPassword: "Choose a password",
    raceSelection: "Choose your race",
    classSelection: "Choose your class",
  };

  // The step's painted scene is still loading (typically one or two frames
  // from the service-worker cache): hold a dark frame instead of flashing the
  // CSS dialog before the art swaps in.
  if (!sceneArt && scenePending) {
    return <div className="login-art-root" aria-hidden="true" />;
  }

  if (loginPrompt.state === "name") {
    const art = sceneArt;
    if (art) {
      return (
        <ArtScene url={art} stageClass={phoneScene ? "login-art-stage--phone" : "login-art-stage--wide"} label="Welcome to AmbonMUD">
          <h1 className="sr-only">AmbonMUD — enter a character name to log in or create, or start a demo</h1>
          {errorForState && (
            <p className="login-art-error" id="login-art-error" role="alert">{errorForState}</p>
          )}
          <form onSubmit={handleSubmit} className="login-art-form">
            <input
              ref={inputRef}
              className="login-art-input"
              type="text"
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              placeholder="Character name"
              autoComplete="off"
              spellCheck={false}
              aria-label="Character name"
              aria-invalid={errorForState ? true : undefined}
              aria-describedby={errorForState ? "login-art-error" : undefined}
            />
            <button
              type="submit"
              className="login-art-hotspot login-art-submit"
              title="Create / Login"
              aria-label="Create or log in"
            />
          </form>
          {loginPrompt.demoEnabled && (
            <button
              type="button"
              className="login-art-hotspot login-art-demo"
              onClick={() => onSubmit("demo")}
              title="Start Demo"
              aria-label="Start demo — play instantly as a guest"
            />
          )}
        </ArtScene>
      );
    }
    return (
      <div className="login-scene-root" role="dialog" aria-modal="true" aria-label="Welcome to AmbonMUD">
        <LoginSceneBackground />
        <main className="login-hero">
          <header className="login-hero-header">
            <h1 className="login-hero-title">AmbonMUD</h1>
            <p className="login-hero-tagline">
              A cozy text-adventure with a hand-crafted world.
            </p>
          </header>

          {errorForState && <p className="login-error login-hero-error">{errorForState}</p>}

          <div className={`login-hero-cards${loginPrompt.demoEnabled ? "" : " login-hero-cards--single"}`}>
            {loginPrompt.demoEnabled && (
              <section className="login-card login-card--demo">
                <div className="login-card-glyph" aria-hidden="true">✦</div>
                <h2 className="login-card-title">Try as a guest</h2>
                <p className="login-card-body">
                  Spawn a demo character in one click — no signup, no setup. Use <code>/claim</code> in-game to keep them.
                </p>
                <button
                  type="button"
                  className="login-card-cta login-card-cta--demo"
                  onClick={() => onSubmit("demo")}
                >
                  <span className="login-card-cta-sparkle" aria-hidden="true" />
                  Start Demo
                </button>
              </section>
            )}

            {loginPrompt.demoEnabled && <span className="login-card-or" aria-hidden="true">or</span>}

            <section className="login-card login-card--signin">
              <div className="login-card-glyph" aria-hidden="true">✧</div>
              <h2 className="login-card-title">Create a full character</h2>
              <p className="login-card-body">
                Pick a name, race, and class. Sign in any time. Returning? Just enter your character name.
              </p>
              <form onSubmit={handleSubmit} className="login-card-form">
                <input
                  ref={inputRef}
                  className="login-input login-card-input"
                  type="text"
                  value={inputValue}
                  onChange={(e) => setInputValue(e.target.value)}
                  placeholder="Character name"
                  autoComplete="off"
                  spellCheck={false}
                  aria-label="Character name"
                />
                <button type="submit" className="login-card-cta login-card-cta--signin">
                  Enter
                </button>
              </form>
            </section>
          </div>

          <footer className="login-hero-footer">
            <span>Open source</span>
            <span className="login-hero-footer-sep" aria-hidden="true">·</span>
            <span>Play in browser or telnet</span>
          </footer>
        </main>
      </div>
    );
  }

  if (loginPrompt.state === "password") {
    const art = sceneArt;
    if (art) {
      return (
        <ArtScene url={art} stageClass={`${phoneScene ? "login-art-stage--phone" : "login-art-stage--book"} login-art-stage--password`} label="Welcome back — enter your password">
          <h1 className="sr-only">Welcome back, {loginPrompt.name} — enter your password</h1>
          <span className="login-art-chip lpw-chip" aria-hidden="true">{loginPrompt.name}</span>
          <form onSubmit={handleSubmit} className="login-art-form">
            <input
              ref={inputRef}
              className="login-art-input login-art-input--password lpw-input"
              type={showPassword ? "text" : "password"}
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              placeholder="Password"
              autoComplete="off"
              aria-label="Password"
              aria-invalid={errorForState ? true : undefined}
              aria-describedby={errorForState ? "login-art-error" : undefined}
            />
            <button
              type="button"
              className="login-art-hotspot login-art-wand lpw-wand"
              onClick={() => setShowPassword((v) => !v)}
              aria-pressed={showPassword}
              title={showPassword ? "Hide password" : "Show password"}
              aria-label={showPassword ? "Hide password" : "Show password"}
            />
            <button type="submit" className="login-art-hotspot lpw-submit" title="Login" aria-label="Log in" />
          </form>
          {errorForState && <p className="login-art-error-chip lpw-error" id="login-art-error" role="alert">{errorForState}</p>}
        </ArtScene>
      );
    }
  }

  if (loginPrompt.state === "newPassword") {
    const art = sceneArt;
    if (art) {
      return (
        <ArtScene url={art} stageClass={`${phoneScene ? "login-art-stage--phone" : "login-art-stage--wide"} login-art-stage--setpw`} label="Choose a password">
          <h1 className="sr-only">Choose a password for {loginPrompt.name}</h1>
          <span className="login-art-chip lsp-chip" aria-hidden="true">{loginPrompt.name}</span>
          <form onSubmit={handleSubmit} className="login-art-form">
            <input
              ref={inputRef}
              className="login-art-input login-art-input--password lsp-input"
              type={showPassword ? "text" : "password"}
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              placeholder="New password"
              autoComplete="new-password"
              aria-label="New password"
              aria-invalid={errorForState ? true : undefined}
              aria-describedby={errorForState ? "login-art-error" : undefined}
            />
            <button
              type="button"
              className="login-art-hotspot login-art-wand lsp-wand"
              onClick={() => setShowPassword((v) => !v)}
              aria-pressed={showPassword}
              title={showPassword ? "Hide password" : "Show password"}
              aria-label={showPassword ? "Hide password" : "Show password"}
            />
            <button type="submit" className="login-art-hotspot lsp-submit" title="Set Password" aria-label="Set password" />
          </form>
          {errorForState && <p className="login-art-error-chip lsp-error" id="login-art-error" role="alert">{errorForState}</p>}
        </ArtScene>
      );
    }
  }

  if (loginPrompt.state === "confirmCreate") {
    const art = sceneArt;
    if (art) {
      return (
        <ArtScene url={art} stageClass={`${phoneScene ? "login-art-stage--phone" : "login-art-stage--book"} login-art-stage--confirm`} label="Create a new character?">
          <h1 className="sr-only">No character named {loginPrompt.name} was found. Create a new character?</h1>
          <span className="login-art-chip lcf-chip" aria-hidden="true">{loginPrompt.name}</span>
          <button
            type="button"
            className="login-art-hotspot lcf-yes"
            onClick={() => handleChoice("yes")}
            title="Yes, create"
            aria-label="Yes, create this character"
          />
          <button
            type="button"
            className="login-art-hotspot lcf-no"
            onClick={() => handleChoice("no")}
            title="No, go back"
            aria-label="No, go back"
          />
          {errorForState && <p className="login-art-error-chip lcf-error" role="alert">{errorForState}</p>}
        </ArtScene>
      );
    }
  }

  if (loginPrompt.state === "raceSelection") {
    const art = sceneArt;
    if (art) {
      return (
        <SlotArtScene
          art={art}
          stageClass="login-art-stage--tall login-art-stage--race"
          label="Choose your race"
          cellPrefix="lrc"
          detailClass="lrc-detail"
          chooseClass="lrc-choose"
          errorClass="lrc-error"
          options={loginPrompt.races}
          error={errorForState}
          onSelect={(index) => handleChoice(String(index + 1))}
        />
      );
    }
  }

  if (loginPrompt.state === "classSelection") {
    const art = sceneArt;
    if (art) {
      return (
        <SlotArtScene
          art={art}
          stageClass="login-art-stage--tall login-art-stage--class"
          label="Choose your class"
          cellPrefix="lcl"
          detailClass="lcl-detail"
          chooseClass="lcl-choose"
          errorClass="lcl-error"
          options={loginPrompt.classes}
          error={errorForState}
          onSelect={(index) => handleChoice(String(index + 1))}
        />
      );
    }
  }

  return (
    <div
      className={`login-modal-backdrop${backdropArt ? " login-modal-backdrop--art" : ""}`}
      style={artVars}
    >
      <div
        className={`login-modal${isWide ? " login-modal--wide" : ""}`}
        role="dialog"
        aria-modal="true"
        aria-label={stepDialogLabel[loginPrompt.state] ?? "Login"}
      >
        <h2 className="login-modal-title">AmbonMUD</h2>

        {loginPrompt.state === "password" && (
          <div className="login-step">
            <p className="login-step-label">
              Welcome back, <strong>{loginPrompt.name}</strong>
            </p>
            {errorForState && <p className="login-error" id="login-error" role="alert">{errorForState}</p>}
            <form onSubmit={handleSubmit} className="login-form">
              <input
                ref={inputRef}
                className="login-input"
                type="password"
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                placeholder="Password"
                aria-label="Password"
                aria-invalid={errorForState ? true : undefined}
                aria-describedby={errorForState ? "login-error" : undefined}
                autoComplete="off"
                autoFocus
              />
              <button type="submit" className="login-button">Login</button>
            </form>
          </div>
        )}

        {loginPrompt.state === "confirmCreate" && (
          <div className="login-step">
            <p className="login-step-label">
              No character named <strong>{loginPrompt.name}</strong> was found.
            </p>
            <p className="login-step-sub">Create a new character?</p>
            {errorForState && <p className="login-error" role="alert">{errorForState}</p>}
            <div className="login-choice-row">
              <button type="button" className="login-button" onClick={() => handleChoice("yes")}>
                Yes, create
              </button>
              <button type="button" className="login-button login-button-secondary" onClick={() => handleChoice("no")}>
                No, go back
              </button>
            </div>
          </div>
        )}

        {loginPrompt.state === "newPassword" && (
          <div className="login-step">
            <p className="login-step-label">
              Choose a password for <strong>{loginPrompt.name}</strong>
            </p>
            {errorForState && <p className="login-error" id="login-error" role="alert">{errorForState}</p>}
            <form onSubmit={handleSubmit} className="login-form">
              <input
                ref={inputRef}
                className="login-input"
                type="password"
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                placeholder="New password"
                aria-label="New password"
                aria-invalid={errorForState ? true : undefined}
                aria-describedby={errorForState ? "login-error" : undefined}
                autoComplete="new-password"
                autoFocus
              />
              <button type="submit" className="login-button">Set Password</button>
            </form>
          </div>
        )}

        {loginPrompt.state === "raceSelection" && (
          <RaceCardGrid
            races={loginPrompt.races}
            error={errorForState}
            onSelect={(index) => handleChoice(String(index + 1))}
          />
        )}

        {loginPrompt.state === "classSelection" && (
          <ClassCardGrid
            classes={loginPrompt.classes}
            error={errorForState}
            onSelect={(index) => handleChoice(String(index + 1))}
          />
        )}
      </div>
    </div>
  );
}

/* ── Race card grid ───────────────────────────────── */

interface RaceCardGridProps {
  races: LoginRaceOption[];
  error: string | null | undefined;
  onSelect: (index: number) => void;
}

function RaceCardGrid({ races, error, onSelect }: RaceCardGridProps) {
  const [selected, setSelected] = useState(0);
  const race = races[selected];

  if (!race) return null;

  return (
    <div className="login-step char-picker">
      <p className="login-step-label">
        Choose your race
        <span className="sr-only"> — select a card to preview it, then activate the Choose button to confirm.</span>
      </p>
      {error && <p className="login-error" role="alert">{error}</p>}

      <div className="char-card-grid">
        {races.map((r, i) => (
          <button
            key={r.id}
            type="button"
            className={`char-card${i === selected ? " selected" : ""}`}
            onClick={() => setSelected(i)}
            onDoubleClick={() => onSelect(i)}
            aria-label={r.name}
            aria-pressed={i === selected}
          >
            {r.image ? (
              <img src={r.image} alt="" aria-hidden="true" className="char-card-img" draggable={false} />
            ) : (
              <div className="char-card-placeholder" />
            )}
            <span className="char-card-label">{r.name}</span>
          </button>
        ))}
      </div>

      <div className="char-detail" key={race.id}>
        <h3 className="char-detail-name">{race.name}</h3>
        {race.stats && <p className="char-detail-stats">{race.stats}</p>}
        {race.description && <p className="char-detail-desc">{race.description}</p>}

        {race.traits && race.traits.length > 0 && (
          <div className="char-detail-traits">
            <span className="char-detail-section-label">Traits</span>
            <ul className="char-detail-trait-list">
              {race.traits.map((t) => {
                const [label, detail] = t.includes(":") ? t.split(":", 2) : [t, ""];
                return (
                  <li key={t} className="char-detail-trait">
                    <span className="char-detail-trait-name">{label}</span>
                    {detail && <span className="char-detail-trait-detail">{detail.trim()}</span>}
                  </li>
                );
              })}
            </ul>
          </div>
        )}

        {race.backstory && <p className="char-detail-backstory">{race.backstory}</p>}
      </div>

      <button
        type="button"
        className="login-button char-picker-select"
        onClick={() => onSelect(selected)}
      >
        Choose {race.name}
      </button>
    </div>
  );
}

/* ── Class card grid ──────────────────────────────── */

interface ClassCardGridProps {
  classes: LoginClassOption[];
  error: string | null | undefined;
  onSelect: (index: number) => void;
}

function ClassCardGrid({ classes, error, onSelect }: ClassCardGridProps) {
  const [selected, setSelected] = useState(0);
  const cls = classes[selected];

  if (!cls) return null;

  return (
    <div className="login-step char-picker">
      <p className="login-step-label">
        Choose your class
        <span className="sr-only"> — select a card to preview it, then activate the Choose button to confirm.</span>
      </p>
      {error && <p className="login-error" role="alert">{error}</p>}

      <div className="char-card-grid">
        {classes.map((c, i) => (
          <button
            key={c.id}
            type="button"
            className={`char-card${i === selected ? " selected" : ""}`}
            onClick={() => setSelected(i)}
            onDoubleClick={() => onSelect(i)}
            aria-label={c.name}
            aria-pressed={i === selected}
          >
            {c.image ? (
              <img src={c.image} alt="" aria-hidden="true" className="char-card-img" draggable={false} />
            ) : (
              <div className="char-card-placeholder" />
            )}
            <span className="char-card-label">{c.name}</span>
          </button>
        ))}
      </div>

      <div className="char-detail" key={cls.id}>
        <h3 className="char-detail-name">{cls.name}</h3>
        {cls.stats && <p className="char-detail-stats">{cls.stats}</p>}
        {cls.description && <p className="char-detail-desc">{cls.description}</p>}
        {cls.backstory && <p className="char-detail-backstory">{cls.backstory}</p>}
      </div>

      <button
        type="button"
        className="login-button char-picker-select"
        onClick={() => onSelect(selected)}
      >
        Choose {cls.name}
      </button>
    </div>
  );
}

/* ── Painted slot-grid scene (race & class selection) ─────────────────── */

interface SlotArtSceneProps {
  art: string;
  stageClass: string;
  label: string;
  /** CSS class prefix for the painted slots (e.g. "lrc" → .lrc-c1 … .lrc-c9). */
  cellPrefix: string;
  detailClass: string;
  chooseClass: string;
  errorClass: string;
  options: Array<LoginRaceOption | LoginClassOption>;
  error: string | null;
  onSelect: (index: number) => void;
}

function SlotArtScene({
  art, stageClass, label, cellPrefix, detailClass, chooseClass, errorClass, options, error, onSelect,
}: SlotArtSceneProps) {
  const [selected, setSelected] = useState(0);
  const current = options[selected];
  if (!current) return null;

  const traits = "traits" in current ? current.traits : undefined;

  return (
    <ArtScene url={art} stageClass={stageClass} label={label}>
      <h1 className="sr-only">{label}</h1>
      {error && <p className={`login-art-error-chip ${errorClass}`} role="alert">{error}</p>}
      {options.map((opt, i) => (
        <button
          key={opt.id}
          type="button"
          className={`login-art-cell ${cellPrefix}-c${i + 1}${i === selected ? " selected" : ""}`}
          onClick={() => setSelected(i)}
          onDoubleClick={() => onSelect(i)}
          aria-label={opt.name}
          aria-pressed={i === selected}
        >
          {opt.image ? (
            <img src={opt.image} alt="" className="login-art-cell-img" draggable={false} />
          ) : (
            <span className="login-art-cell-glyph" aria-hidden="true">✦</span>
          )}
          <span className="login-art-cell-label">{opt.name}</span>
        </button>
      ))}
      <div className={`login-art-detail ${detailClass}`} key={current.id}>
        <h3 className="login-art-detail-name">{current.name}</h3>
        {current.stats && <p className="login-art-detail-stats">{current.stats}</p>}
        {current.description && <p className="login-art-detail-desc">{current.description}</p>}
        {traits && traits.length > 0 && (
          <p className="login-art-detail-traits">
            {traits.map((t) => t.split(":", 1)[0].trim()).join(" · ")}
          </p>
        )}
      </div>
      <button
        type="button"
        className={`login-art-hotspot ${chooseClass}`}
        onClick={() => onSelect(selected)}
        title={`Choose ${current.name}`}
        aria-label={`Choose ${current.name}`}
      />
    </ArtScene>
  );
}

/* ── Magical hero-scene background (name-state only) ──────────────────── */

const FIREFLY_COUNT = 9;

function LoginSceneBackground() {
  return (
    <div className="login-scene" aria-hidden="true">
      <div className="login-scene-sky" />
      <div className="login-scene-aurora" />
      <div className="login-scene-stars login-scene-stars--far" />
      <div className="login-scene-stars login-scene-stars--mid" />
      <div className="login-scene-stars login-scene-stars--near" />
      <div className="login-scene-horizon">
        <div className="login-scene-island login-scene-island--left" />
        <div className="login-scene-island login-scene-island--center" />
        <div className="login-scene-island login-scene-island--right" />
      </div>
      <div className="login-scene-fireflies">
        {Array.from({ length: FIREFLY_COUNT }).map((_, i) => (
          <span key={i} className={`login-scene-firefly login-scene-firefly--${i + 1}`} />
        ))}
      </div>
      <div className="login-scene-vignette" />
    </div>
  );
}
