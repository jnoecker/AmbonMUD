import { useEffect } from "react";
import type { ConsiderResult, MonsterEntry } from "../../types";

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
  onClose: () => void;
  onCommand: (cmd: string) => void;
  onZoomImage: (url: string) => void;
  onQuest: (mobName: string) => void;
  onShop: () => void;
  onVideo: (url: string) => void;
}

interface ManualAction {
  key: string;
  label: string;
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
  onClose,
  onCommand,
  onZoomImage,
  onQuest,
  onShop,
  onVideo,
}: MonsterManualPanelProps) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const { info, name } = monster;
  // Only show stats once the consider result is for *this* creature.
  const c = consider && (consider.mobId === monster.id || consider.mobName === name) ? consider : null;
  const level = c?.mobLevel ?? monster.level;
  const category = c?.mobCategory ?? info?.tier ?? "";

  const actions: ManualAction[] = [];
  if (info?.questComplete) actions.push({ key: "quest", label: "★ Turn In Quest", variant: "primary", run: () => onQuest(name) });
  else if (info?.questAvailable) actions.push({ key: "quest", label: "★ Quest", variant: "primary", run: () => onQuest(name) });
  if (info?.dialogue) actions.push({ key: "talk", label: "Talk", variant: "primary", run: () => { onCommand(`talk ${name}`); onClose(); } });
  if (info?.shopKeeper) actions.push({ key: "shop", label: "Browse Shop", variant: "primary", run: onShop });
  if (monster.canAttack) actions.push({ key: "attack", label: "⚔ Attack", variant: "primary", run: () => { onCommand(`kill ${name}`); onClose(); } });
  if (monster.video) actions.push({ key: "video", label: "▶ Cinematic", variant: "ghost", run: () => onVideo(monster.video!) });
  if (monster.isStaff) actions.push({ key: "possess", label: "✦ Possess", variant: "ghost", run: () => { onCommand(`possess ${name}`); onClose(); } });

  const lvDiff = c ? c.mobLevel - c.playerLevel : 0;
  const shortName = name.split(" ").slice(-1)[0];

  return (
    <div
      className="mm-backdrop"
      role="dialog"
      aria-modal="true"
      aria-label={`${name} — field manual`}
      onClick={onClose}
    >
      <div
        className={`mm-card ${c ? TIER_CLASS[c.rating] ?? "" : ""}`}
        style={bg ? { ["--mm-bg" as string]: `url("${bg}")` } : undefined}
        onClick={(e) => e.stopPropagation()}
      >
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
              {(level != null || category) && (
                <span className="mm-meta">
                  {level != null ? `Lv ${level}` : ""}{level != null && category ? " " : ""}{category}
                </span>
              )}
            </div>
            {c && <span className="mm-badge">{c.ratingLabel}</span>}
          </header>

          {monster.description && <p className="mm-desc">{monster.description}</p>}

          {/* Threat assessment — de-emphasized. */}
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

          <footer className="mm-actions">
            {actions.map((a) => (
              <button
                key={a.key}
                type="button"
                className={`mm-action mm-action-${a.variant}`}
                onClick={a.run}
              >
                {a.label}
              </button>
            ))}
            <button type="button" className="mm-action mm-action-close" onClick={onClose}>‹ Close</button>
          </footer>
        </div>
      </div>
    </div>
  );
}
