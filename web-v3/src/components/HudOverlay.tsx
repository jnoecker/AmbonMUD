import type { Vitals, CharacterInfo } from "../types";

interface HudOverlayProps {
  vitals: Vitals;
  character: CharacterInfo;
  onTap: () => void;
}

function titleCaseWords(str: string): string {
  return str.replace(/\b\w/g, (c) => c.toUpperCase());
}

export function HudOverlay({ vitals, character, onTap }: HudOverlayProps) {
  // Don't render if user is not logged in
  if (!character.name || character.name === "-") return null;

  const level = vitals.level ?? character.level ?? 1;
  const className = character.className ? titleCaseWords(character.className) : "";

  const hpPct = vitals.maxHp > 0 ? (vitals.hp / vitals.maxHp) * 100 : 0;
  const manaPct = vitals.maxMana > 0 ? (vitals.mana / vitals.maxMana) * 100 : 0;

  const xpToNext = vitals.xpToNextLevel ?? 1;
  const xpPct = vitals.xpToNextLevel === null ? 100 : (vitals.xpIntoLevel / Math.max(1, xpToNext)) * 100;

  const hpLabel = `${vitals.hp.toLocaleString()}/${vitals.maxHp.toLocaleString()}`;
  const manaLabel = `${vitals.mana.toLocaleString()}/${vitals.maxMana.toLocaleString()}`;
  const xpLabel =
    vitals.xpToNextLevel === null
      ? "MAX"
      : `${vitals.xpIntoLevel.toLocaleString()}/${vitals.xpToNextLevel.toLocaleString()}`;

  return (
    <div
      className="hud-overlay"
      onClick={onTap}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onTap();
        }
      }}
      aria-label={`Level ${level} ${className}. HP ${vitals.hp} of ${vitals.maxHp}. Mana ${vitals.mana} of ${vitals.maxMana}. Tap to open character panel.`}
    >
      <div className="hud-badge">
        Lv{level} {className}
      </div>
      <div className="hud-bars">
        <div className="hud-bar" aria-hidden="true">
          <span
            className="hud-bar-fill hud-bar-fill--hp"
            style={{ width: `${Math.min(100, Math.max(0, hpPct))}%` }}
          />
          <span className="hud-bar-label">{hpLabel}</span>
        </div>
        <div className="hud-bar" aria-hidden="true">
          <span
            className="hud-bar-fill hud-bar-fill--mana"
            style={{ width: `${Math.min(100, Math.max(0, manaPct))}%` }}
          />
          <span className="hud-bar-label">{manaLabel}</span>
        </div>
        <div className="hud-bar" aria-hidden="true">
          <span
            className="hud-bar-fill hud-bar-fill--xp"
            style={{ width: `${Math.min(100, Math.max(0, xpPct))}%` }}
          />
          <span className="hud-bar-label">{xpLabel}</span>
        </div>
      </div>
    </div>
  );
}
