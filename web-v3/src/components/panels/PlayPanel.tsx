import type { FormEvent, KeyboardEvent, MouseEvent, RefObject } from "react";
import { DirectionIcon, FleeIcon } from "../Icons";
import { isDirection } from "../isDirection";

interface PlayPanelProps {
  preLogin: boolean;
  connected: boolean;
  hasRoomDetails: boolean;
  exits: Array<[string, string]>;
  terminalHostRef: RefObject<HTMLDivElement | null>;
  commandInputRef: RefObject<HTMLInputElement | null>;
  composerValue: string;
  commandPlaceholder: string;
  onTerminalMouseDown: (event: MouseEvent<HTMLDivElement>) => void;
  onComposerChange: (value: string) => void;
  onComposerKeyDown: (event: KeyboardEvent<HTMLInputElement>) => void;
  onSubmitComposer: (event: FormEvent<HTMLFormElement>) => void;
  onMove: (direction: string) => void;
  onFlee: () => void;
}

export function PlayPanel({
  preLogin,
  connected,
  hasRoomDetails,
  exits,
  terminalHostRef,
  commandInputRef,
  composerValue,
  commandPlaceholder,
  onTerminalMouseDown,
  onComposerChange,
  onComposerKeyDown,
  onSubmitComposer,
  onMove,
  onFlee,
}: PlayPanelProps) {
  return (
    <section className="panel panel-play" aria-label="Gameplay console">
      <header className="panel-header"><h2 title="Terminal output and direct command flow.">Play</h2></header>
      {preLogin && (
        <section className="prelogin-banner" aria-label="Login guidance">
          <p className="prelogin-banner-title">Welcome back. Your session is connected.</p>
          <p className="prelogin-banner-text">Use the command bar below to enter your character name and password. World and character panels will populate right after login.</p>
        </section>
      )}
      <div className="terminal-card" onMouseDown={onTerminalMouseDown}><div ref={terminalHostRef} className="terminal-host" aria-label="AmbonMUD terminal" /></div>

      <div className="movement-grid" role="toolbar" aria-label="Room exits">
        {exits.length === 0 ? (
          preLogin ? null : <span className="empty-note">{connected ? "No exits available." : "Reconnect to view exits."}</span>
        ) : (
          exits.map(([direction, target]) => {
            const normalized = direction.toLowerCase();
            const knownDirection = isDirection(normalized);
            return (
              <button
                key={direction}
                type="button"
                className="chip-button"
                title={`Move ${direction} (${target})`}
                onClick={() => onMove(direction)}
              >
                {knownDirection && <DirectionIcon direction={normalized} className="chip-direction-icon" />}
                <span className="chip-label">{direction}</span>
              </button>
            );
          })
        )}
        {connected && hasRoomDetails && (
          <button
            type="button"
            className="chip-button chip-button-utility"
            title="Attempt to flee from combat"
            onClick={onFlee}
          >
            <FleeIcon className="chip-direction-icon" />
            <span className="chip-label">flee</span>
          </button>
        )}
      </div>

      <form className="command-form" onSubmit={onSubmitComposer}>
        <label htmlFor="command-input" className="sr-only">Command input</label>
        <input
          ref={commandInputRef}
          id="command-input"
          className="command-input"
          type="text"
          value={composerValue}
          onChange={(event) => onComposerChange(event.target.value)}
          onKeyDown={onComposerKeyDown}
          placeholder={commandPlaceholder}
          autoComplete="off"
          spellCheck={false}
        />
        <button type="submit" className="soft-button" disabled={!connected}>Send</button>
      </form>
    </section>
  );
}
