import { useEffect, useRef, useState } from "react";
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
  /** True when the player is a pledged Akathavae — Attack becomes Illuminate. */
  akathavaePledged?: boolean;
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
  akathavaePledged = false,
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
  // Deep-link: clicking a creature's floating indicator opens the manual
  // straight to the matching subpanel (dialogue → Talk, quest → Quest). The
  // panel is keyed by creature id in App, so this lazy initializer runs fresh
  // per creature and there's no flash of the info page first.
  const [view, setView] = useState<ManualView>(() =>
    monster.intent === "talk" ? "dialog" : monster.intent === "quest" ? "quest" : "info",
  );
  // Tracks whether the current conversation has produced any node yet, so the
  // initial "…" wait isn't mistaken for the conversation having ended.
  const dialogueSeenRef = useRef(false);
  // Move keyboard focus into the dialog when it opens.
  const cardRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    cardRef.current?.focus();
  }, []);

  // Fire the matching fetch when deep-linked from an indicator. The initial
  // `view` (above) already shows the right subpanel; the setView calls here
  // also cover the rare case where the same creature's intent changes without
  // a remount.
  useEffect(() => {
    if (monster.intent === "talk") {
      onCommand(`talk ${monster.name}`);
      setView("dialog");
    } else if (monster.intent === "quest") {
      onQuest(monster.name);
      setView("quest");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [monster.id, monster.intent]);

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

  // Start each conversation fresh (reset the "seen a node yet" tracker).
  useEffect(() => {
    if (view === "dialog") dialogueSeenRef.current = false;
  }, [view]);

  // Auto-return to the entry only when the conversation ends with nothing left to
  // read — i.e. the server cleared the dialogue outright (a terminal "Maybe
  // later"-style choice, walking away, entering combat). A terminal *node* (the
  // NPC's final line, which has no choices) is deliberately KEPT on screen so its
  // voice-over can play to the end and the player can read it at their own pace;
  // they dismiss it with the Farewell button, the Entry button, or by closing the
  // manual. Auto-closing it on a fixed timer (the old behaviour) cut the voice off
  // after a few words and yanked dialogue-only NPCs back to the description.
  useEffect(() => {
    if (view !== "dialog") return;
    if (dialogue) {
      dialogueSeenRef.current = true;
      return;
    }
    if (!dialogueSeenRef.current) return; // still waiting for the first node ("…")
    const t = window.setTimeout(() => setView("info"), 150);
    return () => window.clearTimeout(t);
  }, [view, dialogue]);

  const { info, name } = monster;
  const skinned = !!bg; // a monster_manual_bg frame is registered
  // Rare-variant colorize: a valid "#rrggbb" tint washes the portrait the same
  // way the canvas sprite is colorized, so albino/verdant/etc. read consistently
  // in the field manual. Invalid/absent tints leave the portrait untouched.
  const variantTint = monster.tint && /^#[0-9a-fA-F]{6}$/.test(monster.tint) ? monster.tint : null;
  // Optional painted portrait frame, shown *only* for variants: its opaque border
  // masks the rectangular colorize seam and makes a rare creature feel special,
  // while ordinary mobs keep the painted card's own alcove. Absent asset → the
  // plain bordered portrait shows through.
  const portraitFrame = variantTint ? serverAssets["monster_manual_portrait_frame"] : undefined;
  // Only show stats once the consider result is for *this* creature.
  const c = consider && (consider.mobId === monster.id || consider.mobName === name) ? consider : null;
  const level = c?.mobLevel ?? monster.level;
  const category = c?.mobCategory ?? info?.tier ?? "";

  // Arcanum badge — anyone keeps a field journal, so every viewer gets the
  // recorded/world-first read. The unclaimed-first tease stays pledged-only:
  // an unpledged journal can never seal that page at the shrines.
  const arcanum =
    info && typeof info.arcanumRecorded === "boolean"
      ? info.arcanumRecorded
        ? { tone: "recorded" as const, text: `Recorded ✓${info.arcanumSource ? ` · ${info.arcanumSource}` : ""}` }
        : info.arcanumFirstBy
          ? { tone: "claimed" as const, text: `First recorded by ${info.arcanumFirstBy}` }
          : akathavaePledged
            ? { tone: "first" as const, text: "★ Unrecorded — be the first" }
            : null
      : null;
  // The kill-side world first sits beside the illumination credit.
  const slainBy = info?.arcanumFirstSlainBy ?? null;

  const actions: ManualAction[] = [];
  if (info?.questComplete) actions.push({ key: "quest", label: "Turn In Quest", glyph: "★", assetKey: "action_quest", variant: "primary", run: () => { onQuest(name); setView("quest"); } });
  else if (info?.questAvailable) actions.push({ key: "quest", label: "Quest", glyph: "★", assetKey: "action_quest", variant: "primary", run: () => { onQuest(name); setView("quest"); } });
  if (info?.dialogue) actions.push({ key: "talk", label: "Talk", glyph: "❝", assetKey: "action_talk", variant: "primary", run: () => { onCommand(`talk ${name}`); setView("dialog"); } });
  if (info?.shopKeeper) actions.push({ key: "shop", label: "Shop", glyph: "❖", assetKey: "action_shop", variant: "primary", run: onShop });
  // The Akathavae pledge replaces violence with illumination — same slot, same
  // creatures, but the action records the subject into the Arcanum instead.
  // The unpledged keep Attack as their primary and get Illuminate as a side
  // action (anyone may keep a field journal, at scaled-down odds). Surfacing
  // the stat-driven success odds (Room.MobInfo `illuminationPct`) matters
  // either way — failure turns the creature hostile, so nobody gambles blind.
  const illumPct = info?.illuminationPct;
  const illuminateAction = (variant: ManualAction["variant"]): ManualAction => ({ key: "illuminate", label: illumPct != null ? `Illuminate · ${illumPct}%` : "Illuminate", glyph: "✒", assetKey: "action_illuminate", variant, run: () => { onCommand(`illuminate ${name}`); close(); } });
  if (monster.canAttack && akathavaePledged) actions.push(illuminateAction("primary"));
  else if (monster.canAttack) {
    actions.push({ key: "attack", label: "Attack", glyph: "⚔", assetKey: "action_attack", variant: "primary", run: () => { onCommand(`kill ${name}`); close(); } });
    actions.push(illuminateAction("ghost"));
  }
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
        ref={cardRef}
        tabIndex={-1}
        className={`mm-card${skinned ? " mm-card-skinned" : ""} ${c ? TIER_CLASS[c.rating] ?? "" : ""}`}
        style={bg ? { ["--mm-bg" as string]: `url("${bg}")` } : undefined}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Mobile-only close: a tall card can fill the screen, leaving no
            backdrop to tap. Desktop keeps the clean click-away. */}
        <button type="button" className="mm-close-x" aria-label="Close" onClick={close}>✕</button>

        {/* Difficulty badge — sits on the painted ribbon when skinned. */}
        {c && <span className="mm-badge">{c.ratingLabel}</span>}

        {/* Framed art (left) */}
        <figure
          className={`mm-figure${variantTint ? " mm-figure-variant" : ""}${portraitFrame ? " mm-figure-framed" : ""}`}
          style={variantTint ? { ["--mm-variant-tint" as string]: variantTint } : undefined}
        >
          {monster.image ? (
            <>
              <img className="mm-image" src={monster.image} alt={name} />
              {/* Colorize wash: grayscale (on .mm-image) × tint (multiply) mirrors
                  the canvas luminance-colorize, so pale variants stay pale. */}
              {variantTint && <span className="mm-variant-wash" aria-hidden="true" />}
              {/* Painted frame sits above the art + wash, masking the colorize seam. */}
              {portraitFrame && <img className="mm-portrait-frame" src={portraitFrame} alt="" aria-hidden="true" />}
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
              <h2 className="mm-name">
                {name}
                {monster.variantName && (
                  <span className="mm-variant-name" style={variantTint ? { color: variantTint } : undefined}>
                    ✦ {monster.variantName}
                  </span>
                )}
              </h2>
              {/* Level/category only matters for things you can fight. */}
              {monster.canAttack && (level != null || category) && (
                <span className="mm-meta">
                  {level != null ? `Lv ${level}` : ""}{level != null && category ? " " : ""}{category}
                </span>
              )}
              {/* Recorded / world-first state — chroniclers decide whether an
                  illumination attempt is worth the aggro risk before acting. */}
              {arcanum && (
                <span className={`mm-arcanum mm-arcanum-${arcanum.tone}`}>{arcanum.text}</span>
              )}
              {slainBy && (
                <span className="mm-arcanum mm-arcanum-claimed">First slain by {slainBy}</span>
              )}
            </div>
          </header>

          {/* Info view — description + threat assessment. */}
          {view === "info" && (
            <>
              {monster.description && <p className="mm-desc">{monster.description}</p>}
              {c ? (
                <div className="mm-assess">
                  <div className="mm-winbar" aria-label="Estimated win chance">
                    <span className="mm-winbar-label">Win chance</span>
                    <div className="mm-winbar-track" role="progressbar" aria-label="Win chance" aria-valuenow={c.winChancePct} aria-valuemin={0} aria-valuemax={100}>
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
                  {dialogue.choices.length > 0 ? (
                    <ul className="mm-dialog-choices">
                      {dialogue.choices.map((ch) => (
                        <li key={ch.index}>
                          <button type="button" className="mm-dialog-choice" onClick={() => onDialogueChoice(ch.index)}>
                            {ch.text}
                          </button>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    // Terminal node: no further choices. Let the player linger on
                    // the line (and its voice-over) and leave on their own terms.
                    <ul className="mm-dialog-choices">
                      <li>
                        <button type="button" className="mm-dialog-choice" onClick={() => { onDialogueEnd(); setView("info"); }}>
                          Farewell.
                        </button>
                      </li>
                    </ul>
                  )}
                </>
              ) : (
                <p className="mm-dialog-text mm-dialog-waiting">…</p>
              )}
            </div>
          )}

          <footer className="mm-actions">
            {/* Journal "Entry" always sits in the row; it returns to the info
                page from quest/dialog (and is a no-op on the entry itself). */}
            <button type="button" className="mm-action mm-action-entry" onClick={() => setView("info")}>
              {actionIcon("action_entry", "❦")}
              <span className="mm-action-label">Entry</span>
            </button>
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
          </footer>
        </div>
      </div>
    </div>
  );
}
