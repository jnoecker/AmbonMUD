import type { RefObject } from "react";
import { PixiCanvas } from "../../canvas/PixiCanvas";
import { CombatLog } from "../CombatLog";
import type { CombatLogMessage } from "../../types";

interface PlayPanelProps {
  preLogin: boolean;
  terminalOverlayRef: RefObject<HTMLDivElement | null>;
  terminalVisible: boolean;
  terminalOpaque: boolean;
  combatLogMessages: CombatLogMessage[];
}

export function PlayPanel({ preLogin, terminalOverlayRef, terminalVisible, terminalOpaque, combatLogMessages }: PlayPanelProps) {
  const overlayClass = terminalVisible
    ? terminalOpaque
      ? "terminal-overlay terminal-overlay-visible terminal-overlay-opaque"
      : "terminal-overlay terminal-overlay-visible"
    : "terminal-overlay";

  return (
    <section className="panel panel-play" aria-label="Gameplay console">
      {preLogin && (
        <section className="prelogin-banner" aria-label="Login guidance">
          <p className="prelogin-banner-title">Log in to begin your adventure.</p>
          <p className="prelogin-banner-text">Enter your character name in the login prompt to get started.</p>
        </section>
      )}
      <div className="terminal-card">
        <PixiCanvas />
        <CombatLog messages={combatLogMessages} />
        <div ref={terminalOverlayRef} className={overlayClass} aria-hidden={!terminalVisible} />
      </div>
    </section>
  );
}
