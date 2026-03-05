import { useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import type { LoginErrorState, LoginPromptState } from "../types";

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

  return (
    <div className="login-modal-backdrop">
      <div className="login-modal" role="dialog" aria-modal="true" aria-label="Login">
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
          <div className="login-step">
            <p className="login-step-label">Choose your race</p>
            {errorForState && <p className="login-error">{errorForState}</p>}
            <div className="login-card-grid">
              {loginPrompt.races.map((race, index) => (
                <button
                  key={race.id}
                  type="button"
                  className="login-card"
                  onClick={() => handleChoice(String(index + 1))}
                >
                  <span className="login-card-name">{race.name}</span>
                  {race.stats && <span className="login-card-stats">{race.stats}</span>}
                </button>
              ))}
            </div>
          </div>
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
