import { useState } from "react";
import type { StylistRace, StylistState } from "../../types";

interface StylistPanelProps {
  stylistState: StylistState | null;
  serverAssets: Record<string, string>;
  onCommand: (cmd: string) => void;
}

/** Tint a stat mod by its kind to echo the concept's colour coding. */
function modClass(key: string): string {
  const k = key.toUpperCase();
  if (k.includes("HP") || k.includes("CRIT")) return "sty-mod-red";
  if (k.includes("MANA")) return "sty-mod-blue";
  if (k.includes("DEX") || k.includes("EVADE") || k.includes("STEALTH") || k.includes("NATURE")) return "sty-mod-green";
  return "sty-mod-gold";
}

/**
 * Stylist — race change parlour, reskinned onto the painted `stylist_bg`
 * frame. Six selectable race cards in the central grid; the painted "Change
 * Race" button (an invisible hotspot) commits the selected race. Cost, current
 * race, and gold are inset into their painted slots.
 */
export function StylistPanel({ stylistState, serverAssets, onCommand }: StylistPanelProps) {
  const [selected, setSelected] = useState<string | null>(null);

  if (!stylistState) {
    return (
      <div className="stylist-board stylist-board-empty">
        <p className="sty-empty">The mirror is dim. Visit a stylist and use the <code>stylist</code> command.</p>
      </div>
    );
  }

  const { feeGold, playerGold, currentRace, races } = stylistState;
  const affordable = playerGold >= feeGold;
  const isCurrentId = (id: string) => id.toUpperCase() === currentRace.toUpperCase();
  const current = races.find((r) => isCurrentId(r.id));
  const selectedRace = races.find((r) => r.id === selected) ?? null;
  const canChange = !!selectedRace && !isCurrentId(selectedRace.id) && affordable;

  return (
    <div className="stylist-board">
      <div className="sty-cost">Race Change Price: <strong>{feeGold.toLocaleString()} Gold</strong></div>

      <div className="sty-grid">
        {races.map((race: StylistRace) => {
          const isCurrent = isCurrentId(race.id);
          const mods = Object.entries(race.statMods).filter(([, v]) => v !== 0).sort(([a], [b]) => a.localeCompare(b));
          return (
            <button
              key={race.id}
              type="button"
              className={`sty-card${selected === race.id ? " is-selected" : ""}${isCurrent ? " is-current" : ""}`}
              onClick={() => setSelected(race.id)}
              aria-pressed={selected === race.id}
            >
              {race.image && <img className="sty-card-portrait" src={race.image} alt="" loading="lazy" />}
              <div className="sty-card-text">
                <span className="sty-card-name">{race.displayName}{isCurrent && <span className="sty-card-tag">current<span className="sr-only"> race</span></span>}</span>
                {race.description && <span className="sty-card-desc">{race.description}</span>}
                {mods.length > 0 && (
                  <span className="sty-card-mods">
                    {mods.map(([k, v]) => (
                      <span key={k} className={`sty-mod ${modClass(k)}`}>{v >= 0 ? `+${v}` : v} {k}</span>
                    ))}
                  </span>
                )}
              </div>
            </button>
          );
        })}
      </div>

      {/* Invisible hotspot over the painted CHANGE RACE button. */}
      <button
        type="button"
        className="sty-change-hit"
        disabled={!canChange}
        aria-label={selectedRace ? `Change race to ${selectedRace.displayName}` : "Select a race to change"}
        title={
          !selectedRace ? "Select a race first"
            : isCurrentId(selectedRace.id) ? "That's already your race"
              : !affordable ? "Not enough gold"
                : `Change to ${selectedRace.displayName}`
        }
        onClick={() => { if (canChange && selectedRace) onCommand(`changerace ${selectedRace.id}`); }}
      />

      {/* Current race, inset into the painted "Your Current Race" card. */}
      <div className="sty-current">
        {current?.image && <img className="sty-current-portrait" src={current.image} alt="" />}
        <div className="sty-current-text">
          <span className="sty-current-name">{current?.displayName ?? currentRace}</span>
          <span className="sty-current-bonuses">Bonuses Active</span>
        </div>
      </div>

      <div className="sty-gold">
        <span className="sty-gold-icon" aria-hidden="true">{serverAssets["coin_icon"] ? <img src={serverAssets["coin_icon"]} alt="" /> : "◉"}</span>
        Your Gold: <strong>{playerGold.toLocaleString()}</strong>
      </div>
    </div>
  );
}
