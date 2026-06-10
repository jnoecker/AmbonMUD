import { useEffect, useState } from "react";
import type { ItemSummary, WhoPlayer } from "../../types";

interface PlayerExaminePanelProps {
  player: WhoPlayer;
  selfName: string;
  /** "who" = examine from the Who board (Tell routes to the Social Board).
   *  "room" = clicked a player in the room (full interaction set). */
  context?: "who" | "room";
  isStaff?: boolean;
  /** Inventory, for the Give picker (room context). */
  inventory?: ItemSummary[];
  /** Optional parchment-frame art (reuses the monster manual's bg). */
  bg?: string;
  /** Resolved server assets, for optional painted action icons. */
  serverAssets?: Record<string, string>;
  onClose: () => void;
  onCommand?: (command: string) => void;
  onTellPlayer: (name: string) => void;
}

interface CardAction {
  key: string;
  label: string;
  glyph: string;
  assetKey: string;
  run: () => void;
}

/**
 * Player "field manual" card — opens a clicked player in the monster-manual
 * style: their sprite in the portrait spot, base info, RP description, and a
 * row of interaction icons. From the Who board it's a light examine (Tell);
 * from a room click it's the full context menu (Duel / Tell / Whisper / Give /
 * Group Invite / Friend, plus Possess for staff), with Tell/Whisper revealing
 * an inline message box and Give an inline inventory picker.
 */
export function PlayerExaminePanel({
  player,
  selfName,
  context = "who",
  isStaff = false,
  inventory = [],
  bg,
  serverAssets = {},
  onClose,
  onCommand,
  onTellPlayer,
}: PlayerExaminePanelProps) {
  const [compose, setCompose] = useState<{ mode: "tell" | "whisper"; draft: string } | null>(null);
  const [giveOpen, setGiveOpen] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      if (compose) setCompose(null);
      else if (giveOpen) setGiveOpen(false);
      else onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, compose, giveOpen]);

  const name = player.name;
  const isSelf = name.localeCompare(selfName, undefined, { sensitivity: "accent" }) === 0;
  const meta = [player.playerClass, player.race].filter(Boolean).join(" · ");

  const send = (cmd: string) => { onCommand?.(cmd); onClose(); };

  const actions: CardAction[] = [];
  if (context === "room") {
    if (!isSelf) actions.push({ key: "duel", label: "Duel", glyph: "⚔", assetKey: "action_duel", run: () => send(`duel ${name}`) });
    if (!isSelf) actions.push({ key: "tell", label: "Tell", glyph: "✉", assetKey: "action_tell", run: () => { setGiveOpen(false); setCompose({ mode: "tell", draft: "" }); } });
    if (!isSelf) actions.push({ key: "whisper", label: "Whisper", glyph: "❝", assetKey: "action_whisper", run: () => { setGiveOpen(false); setCompose({ mode: "whisper", draft: "" }); } });
    if (!isSelf) actions.push({ key: "give", label: "Give", glyph: "⊞", assetKey: "action_give", run: () => { setCompose(null); setGiveOpen((v) => !v); } });
    if (!isSelf) actions.push({ key: "group", label: "Invite", glyph: "⊕", assetKey: "action_groupinvite", run: () => send(`group invite ${name}`) });
    if (!isSelf) actions.push({ key: "friend", label: "Friend", glyph: "♥", assetKey: "action_friend", run: () => send(`friend add ${name}`) });
    if (isStaff) actions.push({ key: "possess", label: "Possess", glyph: "✦", assetKey: "action_possess", run: () => send(`possess ${name}`) });
  } else if (!isSelf) {
    actions.push({ key: "tell", label: "Send Tell", glyph: "✉", assetKey: "action_tell", run: () => { onTellPlayer(name); onClose(); } });
  }

  const icon = (a: CardAction) => {
    const url = serverAssets[a.assetKey];
    if (url) return <img className="mm-action-icon" src={url} alt="" aria-hidden="true" />;
    return <span className="mm-action-glyph" aria-hidden="true">{a.glyph}</span>;
  };

  return (
    <div className="mm-backdrop" role="dialog" aria-modal="true" aria-label={`${name} — field manual`} onClick={onClose}>
      <div
        className={`mm-card${bg ? " mm-card-skinned" : ""}`}
        style={bg ? { ["--mm-bg" as string]: `url("${bg}")` } : undefined}
        onClick={(e) => e.stopPropagation()}
      >
        <button type="button" className="mm-close-x" aria-label="Close" onClick={onClose}>✕</button>

        <figure className="mm-figure">
          {player.sprite ? (
            <img className="mm-image" src={player.sprite} alt={name} />
          ) : (
            <div className="mm-image mm-image-empty" aria-hidden="true" />
          )}
        </figure>

        <div className="mm-content">
          <header className="mm-head">
            <div className="mm-titles">
              <h2 className="mm-name">{name}</h2>
              <span className="mm-meta">
                {meta}{(meta && player.level != null) ? " · " : ""}{player.level != null ? `Lv ${player.level}` : ""}
              </span>
            </div>
          </header>

          {player.title && <p className="mm-player-title">{player.title}</p>}

          {player.description
            ? <p className="mm-desc">{player.description}</p>
            : <p className="mm-assessing">This adventurer keeps their story to themselves.</p>}

          {/* Inline composer for Tell / Whisper. */}
          {compose && (
            <form
              className="mm-compose"
              onSubmit={(e) => {
                e.preventDefault();
                const msg = compose.draft.trim();
                if (msg) onCommand?.(`${compose.mode} ${name} ${msg}`);
                setCompose(null);
                onClose();
              }}
            >
              <input
                className="mm-compose-input"
                type="text"
                autoFocus
                value={compose.draft}
                onChange={(e) => setCompose({ ...compose, draft: e.target.value })}
                placeholder={`${compose.mode === "tell" ? "Tell" : "Whisper to"} ${name}…`}
                aria-label={`${compose.mode} message`}
              />
              <button type="submit" className="mm-compose-send" disabled={!compose.draft.trim()}>Send</button>
            </form>
          )}

          {/* Inline inventory picker for Give. */}
          {giveOpen && (
            <div className="mm-give" role="group" aria-label="Give an item">
              {inventory.length === 0 ? (
                <p className="mm-give-empty">Your pack is empty.</p>
              ) : (
                <ul className="mm-give-list">
                  {inventory.map((it) => (
                    <li key={it.id}>
                      <button type="button" className="mm-give-item" onClick={() => send(`give ${it.keyword} ${name}`)}>
                        {it.name}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          <footer className="mm-actions">
            {actions.map((a) => (
              <button key={a.key} type="button" className="mm-action mm-action-primary" onClick={a.run}>
                {icon(a)}
                <span className="mm-action-label">{a.label}</span>
              </button>
            ))}
            <button type="button" className="mm-action mm-action-ghost" onClick={onClose}>
              <span className="mm-action-glyph" aria-hidden="true">✕</span>
              <span className="mm-action-label">Close</span>
            </button>
          </footer>
        </div>
      </div>
    </div>
  );
}
