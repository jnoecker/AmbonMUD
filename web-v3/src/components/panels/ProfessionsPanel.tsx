import { useEffect, useRef } from "react";
import type { CraftingSkill } from "../../types";

interface ProfessionsPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  skills: CraftingSkill[];
  onLoadSkills: () => void;
}

/** A glyph per known profession; falls back to a gathering/crafting mark. */
const GLYPHS: Record<string, string> = {
  mining: "⛏",
  herbalism: "⚘",
  fishing: "\u{1F41F}",
  smithing: "⚒",
  tailoring: "\u{1F9F5}",
  alchemy: "⚗",
  woodcutting: "\u{1FA93}",
  cooking: "\u{1F372}",
  leatherworking: "\u{1F9F0}",
  enchanting: "✨",
};

function glyph(name: string, type: string): string {
  return GLYPHS[name.toLowerCase()] ?? (type === "gathering" ? "⚘" : "⚒");
}

/**
 * Professions — reskinned onto the painted `professions_bg` book. Each practiced
 * profession shows its glyph, name, gathering/crafting class, level badge, and
 * an XP bar. Opened from the character popout (like Prestige / Achievements).
 */
export function ProfessionsPanel({ connected, hasCharacterProfile, skills, onLoadSkills }: ProfessionsPanelProps) {
  const ready = connected && hasCharacterProfile;
  const loaded = useRef(false);
  useEffect(() => {
    if (ready && !loaded.current) {
      loaded.current = true;
      onLoadSkills();
    }
  }, [ready, onLoadSkills]);

  return (
    <div className="prof-board">
      <div className="prof-list">
        {!ready ? (
          <p className="prof-empty">Log in to view your professions.</p>
        ) : skills.length === 0 ? (
          <p className="prof-empty">You have not practiced any professions yet.</p>
        ) : (
          skills.map((s) => {
            const isMax = s.level >= s.maxLevel;
            const pct = isMax ? 100 : s.xpToNext > 0 ? Math.min(100, Math.round((s.xp / s.xpToNext) * 100)) : 0;
            return (
              <div key={s.id} className={`prof-row prof-row-${s.type}`}>
                <span className="prof-icon" aria-hidden="true">{glyph(s.name, s.type)}</span>
                <span className="prof-name-block">
                  <span className="prof-name">{s.name}</span>
                  <span className={`prof-cat prof-cat-${s.type}`}>
                    {s.type === "gathering" ? "Gathering" : "Crafting"}
                  </span>
                </span>
                <span className={`prof-badge prof-badge-${s.type}`}>
                  <span className="prof-badge-lv">Lv.</span>
                  {s.level}
                </span>
                <span className="prof-xp">
                  <span className="prof-xp-text">
                    {isMax ? "Mastered" : `XP: ${s.xp.toLocaleString()} / ${s.xpToNext.toLocaleString()}`}
                  </span>
                  <span className={`prof-bar prof-bar-${s.type}`}>
                    <span className="prof-bar-fill" style={{ width: `${pct}%` }} />
                  </span>
                </span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
