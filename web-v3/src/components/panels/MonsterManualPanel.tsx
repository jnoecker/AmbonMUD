import { useEffect, useState } from "react";
import type { ConsiderResult, DialogueState, MonsterEntry, QuestAvailable } from "../../types";
import { QuestOfferPanel } from "./QuestOfferPanel";

const TIER_CLASS: Record<string, string> = {
  TRIVIAL: "consider-tier-trivial",
  EASY: "consider-tier-easy",
  FAVORED: "consider-tier-favored",
  EVEN: "consider-tier-even",
  RISKY: "consider-tier-risky",
  DANGEROUS: "consider-tier-dangerous",
  SUICIDAL: "consider-tier-suicidal",
};

interface MonsterManualPanelProps {
  monster: MonsterEntry;
  consider: ConsiderResult | null;
  /** Optional parchment-frame art (server asset monster_manual_bg). */
  bg?: string;
  /** Resolved server assets, for the optional action-button icons. */
  serverAssets: Record<string, string>;
  connected: boolean;
  /** Quest offers + dialogue render inside the manual (right column). */
  questsAvailable: QuestAvailable[];
  dialogue: DialogueState | null;
  onClose: () => void;
  onCommand: (cmd: string) => void;
  onZoomImage: (url: string) => void;
  /** Fetch quest offers for this NPC (no longer opens a separate drawer). */
  onQuest: (mobName: string) => void;
  onAcceptQuest: (questId: string) => void;
  onTurnInQuest: (questId: string) => void;
  onDialogueChoice: (index: number) => void;
  onDialogueDismiss: () => void;
  /** Clear the conversation without sending `bye` (it already ended server-side). */
  onDialogueEnd: () => void;
  onShop: () => void;
  onVideo: (url: string) => void;
}

type ManualView = "info" | "quest" | "dialog";

interface ManualAction {
  key: string;
  label: string;
  /** Unicode fallback shown when the icon asset isn't registered. */
  glyph: string;
  /** Server-asset key for a custom icon (sword, scroll, …). */
  assetKey: string;
  variant: "primary" | "ghost";
  run: () => void;
}

/**
 * Bestiary-style "field manual" page for a clicked creature: a parchment card
 * with the framed (zoomable) art, name + level + difficulty badge, a prominent
 * description, a de-emphasized threat assessment, and the real actions
 * (Attack / Talk / Quest / Shop) plus Close.
 */
export function MonsterManualPanel({
  monster,
  consider,
  bg,
  serverAssets,
  connected,
  questsAvailable,
  dialogue,
  onClose,
  onCommand,
  onZoomImage,
  onQuest,
  onAcceptQuest,
  onTurnInQuest,
  onDialogueChoice,
  onDialogueDismiss,
  onDialogueEnd,
  onShop,
  onVideo,
}: MonsterManualPanelProps) {
  const [view, setView] = useState<ManualView>("info");

  // Closing mid-conversation should also end the dialogue (so the in-canvas
  // overlay doesn't resurface once the manual is gone).
  const close = () => {
    if (dialogue) onDialogueDismiss();
    onClose();
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (view !== "info") setView("info");
        else close();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }); // re-bind each render so `view`/`dialogue` stay current

  // When a conversation reaches a terminal node (no choices), show the closing
  // line briefly, then return to the entry and clear the dialogue.
  useEffect(() => {
    if (view !== "dialog" || !dialogue || dialogue.choices.length > 0) return;
    const t = window.setTimeout(() => {
      onDialogueEnd();
      setView("info");
    }, 1700);
    return () => window.clearTimeout(t);
  }, [view, dialogue]); // eslint-disable-line react-hooks/exhaustive-deps

  const { info, name } = monster;
  const skinned = !!bg; // a monster_manual_bg frame is registered
  // Only show stats once the consider result is for *this* creature.
  const c = consider && (consider.mobId === monster.id || consider.mobName === name) ? consider : null;
  const level = c?.mobLevel ?? monster.level;
  const category = c?.mobCategory ?? info?.tier ?? "";

  const actions: ManualAction[] = [];
  if (info?.questComplete) actions.push({ key: "quest", label: "Turn In Quest", glyph: "★", assetKey: "action_quest", variant: "primary", run: () => { onQuest(name); setView("quest"); } });
  else if (info?.questAvailable) actions.push({ key: "quest", label: "Quest", glyph: "★", assetKey: "action_quest", variant: "primary", run: () => { onQuest(name); setView("quest"); } });
  if (info?.dialogue) actions.push({ key: "talk", label: "Talk", glyph: "", assetKey: "action_talk", variant: "primary", run: () => { onCommand(`talk ${name}`); setView("dialog"); } });
  if (info?.shopKeeper) actions.push({ key: "shop", label: "Browse Shop", glyph: "", assetKey: "action_shop", variant: "primary", run: onShop });
  if (monster.canAttack) actions.push({ key: "attack", label: "Attack", glyph: "⚔", assetKey: "action_attack", variant: "primary", run: () => { onCommand(`kill ${name}`); close(); } });
  if (monster.video) actions.push({ key: "video", label: "Cinematic", glyph: "▶", assetKey: "action_cinematic", variant: "ghost", run: () => onVideo(monster.video!) });
  if (monster.isStaff) actions.push({ key: "possess", label: "Possess", glyph: "✦", assetKey: "action_possess", variant: "ghost", run: () => { onCommand(`possess ${name}`); close(); } });

  // An action icon: custom asset when registered, else the unicode glyph.
  const actionIcon = (assetKey: string, glyph: string) => {
    const url = serverAssets[assetKey];
    if (url) return <img className="mm-action-icon" src={url} alt="" aria-hidden="true" />;
    if (glyph) return <span className="mm-action-glyph" aria-hidden="true">{glyph}</span>;
    return null;
  };

  const lvDiff = c ? c.mobLevel - c.playerLevel : 0;
  const shortName = name.split(" ").slice(-1)[0];

  // Accepting / turning in returns to the entry (the sub-window auto-closes).
  const acceptQuest = (id: string) => { onAcceptQuest(id); setView("info"); };
  const turnInQuest = (id: string) => { onTurnInQuest(id); setView("info"); };

  return (
    <div
      className="mm-backdrop"
      role="dialog"
      aria-modal="true"
      aria-label={`${name} — field manual`}
      onClick={close}
    >
      <div
        className={`mm-card${skinned ? " mm-card-skinned" : ""} ${c ? TIER_CLASS[c.rating] ?? "" : ""}`}
        style={bg ? { ["--mm-bg" as string]: `url("${bg}")` } : undefined}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Difficulty badge — sits on the painted ribbon when skinned. */}
        {c && <span className="mm-badge">{c.ratingLabel}</span>}

        {/* Framed art (left) */}
        <figure className="mm-figure">
          {monster.image ? (
            <>
              <img className="mm-image" src={monster.image} alt={name} />
              <button
                type="button"
                className="mm-zoom"
                title="Enlarge"
                aria-label={`Enlarge ${name}`}
                onClick={() => onZoomImage(monster.image!)}
              >
                ⌖
              </button>
            </>
          ) : (
            <div className="mm-image mm-image-empty" aria-hidden="true" />
          )}
        </figure>

        {/* Manual entry (right) */}
        <div className="mm-content">
          <header className="mm-head">
            <div className="mm-titles">
              <h2 className="mm-name">{name}</h2>
              {/* Level/category only matters for things you can fight. */}
              {monster.canAttack && (level != null || category) && (
                <span className="mm-meta">
                  {level != null ? `Lv ${level}` : ""}{level != null && category ? " " : ""}{category}
                </span>
              )}
            </div>
          </header>

          {view !== "info" && (
            <button type="button" className="mm-back" onClick={() => setView("info")}>‹ Entry</button>
          )}

          {/* Info view — description + threat assessment. */}
          {view === "info" && (
            <>
              {monster.description && <p className="mm-desc">{monster.description}</p>}
              {c ? (
                <div className="mm-assess">
                  <div className="mm-winbar" aria-label="Estimated win chance">
                    <span className="mm-winbar-label">Win chance</span>
                    <div className="mm-winbar-track" role="progressbar" aria-valuenow={c.winChancePct} aria-valuemin={0} aria-valuemax={100}>
                      <span className="mm-winbar-fill" style={{ width: `${c.winChancePct}%` }} />
                    </div>
                    <span className="mm-winbar-pct">{c.winChancePct}%</span>
                  </div>
                  <dl className="mm-stats">
                    <div className="mm-stat"><dt>Your hit</dt><dd>~{c.playerAvgDamage}</dd></div>
                    <div className="mm-stat"><dt>Their hit</dt><dd>~{c.mobAvgDamage}</dd></div>
                    <div className="mm-stat"><dt>Kill {shortName}</dt><dd>{c.hitsToKillMob} hits</dd></div>
                    <div className="mm-stat"><dt>Kill you</dt><dd>{c.hitsToKillPlayer} hits</dd></div>
                    {c.dodgeChancePct > 0 && <div className="mm-stat"><dt>Dodge</dt><dd>{c.dodgeChancePct}%</dd></div>}
                    <div className="mm-stat"><dt>Lvl diff</dt><dd>{lvDiff > 0 ? "+" : ""}{lvDiff}</dd></div>
                  </dl>
                </div>
              ) : monster.canAttack ? (
                <p className="mm-assessing">Assessing threat…</p>
              ) : null}
            </>
          )}

          {/* Quest view — offers + turn-ins for this NPC. */}
          {view === "quest" && (
            <div className="mm-view">
              <QuestOfferPanel
                connected={connected}
                questsAvailable={questsAvailable}
                onAcceptQuest={acceptQuest}
                onTurnInQuest={turnInQuest}
              />
            </div>
          )}

          {/* Dialog view — the NPC's conversation. */}
          {view === "dialog" && (
            <div className="mm-view mm-dialog">
              {dialogue ? (
                <>
                  <p className="mm-dialog-text">{dialogue.text}</p>
                  {dialogue.choices.length > 0 && (
                    <ul className="mm-dialog-choices">
                      {dialogue.choices.map((ch) => (
                        <li key={ch.index}>
                          <button type="button" className="mm-dialog-choice" onClick={() => onDialogueChoice(ch.index)}>
                            {ch.text}
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              ) : (
                <p className="mm-dialog-text mm-dialog-waiting">…</p>
              )}
            </div>
          )}

          <footer className="mm-actions">
            {actions.map((a) => (
              <button
                key={a.key}
                type="button"
                className={`mm-action mm-action-${a.variant}`}
                onClick={a.run}
              >
                {actionIcon(a.assetKey, a.glyph)}
                <span className="mm-action-label">{a.label}</span>
              </button>
            ))}
            <button type="button" className="mm-action mm-action-close" onClick={close}>
              {actionIcon("action_close", "‹")}
              <span className="mm-action-label">Close</span>
            </button>
          </footer>
        </div>
      </div>
    </div>
  );
}
