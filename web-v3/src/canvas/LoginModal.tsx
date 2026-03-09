import { useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import type { LoginErrorState, LoginPromptState, LoginRaceOption } from "../types";

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

  const isWide = loginPrompt.state === "raceSelection";

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
          <RaceCarousel
            races={loginPrompt.races}
            error={errorForState}
            onSelect={(index) => handleChoice(String(index + 1))}
          />
        )}

        {loginPrompt.state === "classSelection" && (
          <div className="login-step">
            <p className="login-step-label">Choose your class</p>
            {errorForState && <p className="login-error">{errorForState}</p>}
            <div className="login-card-grid">
              {loginPrompt.classes.map((cls, index) => (
                <button
                  key={cls.id}
                  type="button"
                  className="login-card"
                  onClick={() => handleChoice(String(index + 1))}
                >
                  <span className="login-card-name">{cls.name}</span>
                  {cls.stats && <span className="login-card-stats">{cls.stats}</span>}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/* ── Race carousel ─────────────────────────────────── */

interface RaceCarouselProps {
  races: LoginRaceOption[];
  error: string | null | undefined;
  onSelect: (index: number) => void;
}

function RaceCarousel({ races, error, onSelect }: RaceCarouselProps) {
  const [current, setCurrent] = useState(0);
  const race = races[current];

  const prev = useCallback(() => {
    setCurrent((i) => (i - 1 + races.length) % races.length);
  }, [races.length]);

  const next = useCallback(() => {
    setCurrent((i) => (i + 1) % races.length);
  }, [races.length]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "ArrowLeft") { prev(); e.preventDefault(); }
      if (e.key === "ArrowRight") { next(); e.preventDefault(); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [prev, next]);

  if (!race) return null;

  return (
    <div className="login-step race-carousel">
      <p className="login-step-label">Choose your race</p>
      {error && <p className="login-error">{error}</p>}

      <div className="race-carousel-nav">
        <button
          type="button"
          className="race-carousel-arrow"
          onClick={prev}
          aria-label="Previous race"
        >
          &#8249;
        </button>

        <div className="race-carousel-card" key={race.id}>
          {race.image && (
            <div className="race-carousel-portrait">
              <img
                src={race.image}
                alt={race.name}
                className="race-carousel-img"
                draggable={false}
              />
            </div>
          )}

          <h3 className="race-carousel-name">{race.name}</h3>

          {race.stats && (
            <p className="race-carousel-stats">{race.stats}</p>
          )}

          {race.description && (
            <p className="race-carousel-desc">{race.description}</p>
          )}

          {race.traits && race.traits.length > 0 && (
            <div className="race-carousel-traits">
              <span className="race-carousel-section-label">Traits</span>
              <ul className="race-carousel-trait-list">
                {race.traits.map((t) => {
                  const [label, detail] = t.includes(":") ? t.split(":", 2) : [t, ""];
                  return (
                    <li key={t} className="race-carousel-trait">
                      <span className="race-carousel-trait-name">{label}</span>
                      {detail && <span className="race-carousel-trait-detail">{detail.trim()}</span>}
                    </li>
                  );
                })}
              </ul>
            </div>
          )}

          {race.backstory && (
            <p className="race-carousel-backstory">{race.backstory}</p>
          )}
        </div>

        <button
          type="button"
          className="race-carousel-arrow"
          onClick={next}
          aria-label="Next race"
        >
          &#8250;
        </button>
      </div>

      <div className="race-carousel-dots">
        {races.map((r, i) => (
          <button
            key={r.id}
            type="button"
            className={`race-carousel-dot${i === current ? " active" : ""}`}
            onClick={() => setCurrent(i)}
            aria-label={r.name}
          />
        ))}
      </div>

      <button
        type="button"
        className="login-button race-carousel-select"
        onClick={() => onSelect(current)}
      >
        Choose {race.name}
      </button>
    </div>
  );
}
