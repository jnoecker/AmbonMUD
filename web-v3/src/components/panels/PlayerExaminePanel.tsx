import { useEffect } from "react";
import type { WhoPlayer } from "../../types";

interface PlayerExaminePanelProps {
  player: WhoPlayer;
  selfName: string;
  /** Optional parchment-frame art (reuses the monster manual's bg). */
  bg?: string;
  onClose: () => void;
  onTellPlayer: (name: string) => void;
}

/**
 * "Examine" card — opens a clicked player in the monster-manual style guide:
 * their sprite in the portrait spot, base info (class / race / level), and
 * their RP description. Reuses the `.mm-*` field-manual styling so players read
 * as just another entry in the bestiary.
 */
export function PlayerExaminePanel({ player, selfName, bg, onClose, onTellPlayer }: PlayerExaminePanelProps) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const isSelf = player.name.localeCompare(selfName, undefined, { sensitivity: "accent" }) === 0;
  const meta = [player.playerClass, player.race].filter(Boolean).join(" · ");

  return (
    <div className="mm-backdrop" role="dialog" aria-modal="true" aria-label={`${player.name} — field manual`} onClick={onClose}>
      <div
        className={`mm-card${bg ? " mm-card-skinned" : ""}`}
        style={bg ? { ["--mm-bg" as string]: `url("${bg}")` } : undefined}
        onClick={(e) => e.stopPropagation()}
      >
        <button type="button" className="mm-close-x" aria-label="Close" onClick={onClose}>✕</button>

        <figure className="mm-figure">
          {player.sprite ? (
            <img className="mm-image" src={player.sprite} alt={player.name} />
          ) : (
            <div className="mm-image mm-image-empty" aria-hidden="true" />
          )}
        </figure>

        <div className="mm-content">
          <header className="mm-head">
            <div className="mm-titles">
              <h2 className="mm-name">{player.name}</h2>
              <span className="mm-meta">
                {meta}{(meta && player.level != null) ? " · " : ""}{player.level != null ? `Lv ${player.level}` : ""}
              </span>
            </div>
          </header>

          {player.title && <p className="mm-player-title">{player.title}</p>}

          {player.description
            ? <p className="mm-desc">{player.description}</p>
            : <p className="mm-assessing">This adventurer keeps their story to themselves.</p>}

          <footer className="mm-actions">
            {!isSelf && (
              <button type="button" className="mm-action mm-action-primary" onClick={() => { onTellPlayer(player.name); onClose(); }}>
                <span className="mm-action-glyph" aria-hidden="true">✉</span>
                <span className="mm-action-label">Send Tell</span>
              </button>
            )}
            <button type="button" className="mm-action mm-action-ghost" onClick={onClose}>
              <span className="mm-action-label">Close</span>
            </button>
          </footer>
        </div>
      </div>
    </div>
  );
}
