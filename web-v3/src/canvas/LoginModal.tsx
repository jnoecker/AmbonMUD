import { useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import type { LoginClassOption, LoginErrorState, LoginPromptState, LoginRaceOption } from "../types";

interface LoginModalProps {
  loginPrompt: LoginPromptState;
  loginError: LoginErrorState | null;
  onSubmit: (value: string) => void;
}

export function LoginModal({ loginPrompt, loginError, onSubmit }: LoginModalProps) {
  const [inputValue, setInputValue] = useState("");
  const [prevState, setPrevState] = useState(loginPrompt.state);
  const inputRef = useRef<HTMLInputElement | null>(null);

  // Clear input when login step changes
  if (loginPrompt.state !== prevState) {
    setPrevState(loginPrompt.state);
    setInputValue("");
  }

  useEffect(() => {
    inputRef.current?.focus();
  }, [loginPrompt.state]);

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

  const errorForState = loginError?.state === loginPrompt.state ? loginError.message : null;

  const isWide = loginPrompt.state === "raceSelection" || loginPrompt.state === "classSelection";

  return (
    <div className="login-modal-backdrop">
      <div
        className={`login-modal${isWide ? " login-modal--wide" : ""}`}
        role="dialog"
        aria-modal="true"
        aria-label="Login"
      >
        <h2 className="login-modal-title">AmbonMUD</h2>

        {loginPrompt.state === "name" && (
          <div className="login-step">
            <p className="login-step-label">Enter your character name</p>
            {errorForState && <p className="login-error">{errorForState}</p>}
            <form onSubmit={handleSubmit} className="login-form">
              <input
                ref={inputRef}
                className="login-input"
                type="text"
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                placeholder="Character name"
                autoComplete="off"
                spellCheck={false}
                autoFocus
              />
              <button type="submit" className="login-button">Enter</button>
            </form>
          </div>
        )}

        {loginPrompt.state === "password" && (
          <div className="login-step">
            <p className="login-step-label">
              Welcome back, <strong>{loginPrompt.name}</strong>
            </p>
            {errorForState && <p className="login-error">{errorForState}</p>}
            <form onSubmit={handleSubmit} className="login-form">
              <input
                ref={inputRef}
                className="login-input"
                type="password"
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                placeholder="Password"
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
            {errorForState && <p className="login-error">{errorForState}</p>}
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
            {errorForState && <p className="login-error">{errorForState}</p>}
            <form onSubmit={handleSubmit} className="login-form">
              <input
                ref={inputRef}
                className="login-input"
                type="password"
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                placeholder="New password"
                autoComplete="off"
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
      <p className="login-step-label">Choose your race</p>
      {error && <p className="login-error">{error}</p>}

      <div className="char-card-grid">
        {races.map((r, i) => (
          <button
            key={r.id}
            type="button"
            className={`char-card${i === selected ? " selected" : ""}`}
            onClick={() => setSelected(i)}
            onDoubleClick={() => onSelect(i)}
            aria-label={r.name}
          >
            {r.image ? (
              <img src={r.image} alt={r.name} className="char-card-img" draggable={false} />
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
      <p className="login-step-label">Choose your class</p>
      {error && <p className="login-error">{error}</p>}

      <div className="char-card-grid">
        {classes.map((c, i) => (
          <button
            key={c.id}
            type="button"
            className={`char-card${i === selected ? " selected" : ""}`}
            onClick={() => setSelected(i)}
            onDoubleClick={() => onSelect(i)}
            aria-label={c.name}
          >
            {c.image ? (
              <img src={c.image} alt={c.name} className="char-card-img" draggable={false} />
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
