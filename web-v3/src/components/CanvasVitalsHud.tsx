import type { ItemSummary, Vitals } from "../types";
import type { AudioEngine } from "../hooks/useAudioEngine";
import { AudioControls } from "./AudioControls";
import { selectQuickPotion } from "../combat/quickPotion";
import { percent } from "../utils";

interface CanvasVitalsHudProps {
  vitals: Vitals;
  inventory: ItemSummary[];
  serverAssets: Record<string, string>;
  onCommand: (cmd: string) => void;
  audio: AudioEngine;
}

/**
 * Horizontal HP / MP / Gold strip across the top-left of the canvas. Laid out
 * in a single row so it claims minimal vertical space and frees the left edge
 * for the kiosk column.
 */
export function CanvasVitalsHud({ vitals, inventory, serverAssets, onCommand, audio }: CanvasVitalsHudProps) {
  const barBg = serverAssets["vitals_bar_bg"];
  const hpPick = selectQuickPotion(inventory, vitals.maxHp - vitals.hp, "hp");
  const manaPick = selectQuickPotion(inventory, vitals.maxMana - vitals.mana, "mana");
  const hpDisabled = vitals.hp >= vitals.maxHp || hpPick === null;
  const manaDisabled = vitals.mana >= vitals.maxMana || manaPick === null;
  const hpTitle = vitals.hp >= vitals.maxHp
    ? "HP is full"
    : hpPick
      ? `Quick Heal — ${hpPick.item.name} (+${hpPick.amount} HP${hpPick.fullyRestores ? ", fully restores" : ""})`
      : "No healing potion in inventory";
  const manaTitle = vitals.mana >= vitals.maxMana
    ? "Mana is full"
    : manaPick
      ? `Quick Mana — ${manaPick.item.name} (+${manaPick.amount} mana${manaPick.fullyRestores ? ", fully restores" : ""})`
      : "No mana potion in inventory";

  // Skinned mode: the vitals_bar_bg art carries the carved slots/buttons; we
  // crop it to its content box and overlay only the live bits (drain shadows,
  // numbers, and invisible click targets on the painted heal/mana/gear buttons).
  if (barBg) {
    const hpPct = percent(vitals.hp, vitals.maxHp);
    const mpPct = percent(vitals.mana, vitals.maxMana);
    return (
      <div className="canvas-vitals-skin">
        <img className="vskin-art" src={barBg} alt="" aria-hidden="true" />
        <div className="vskin-overlay">
          <div className="vskin-slot vskin-slot-hp" title={`HP: ${vitals.hp}/${vitals.maxHp}`}>
            <span className="vskin-drain" style={{ left: `${hpPct}%` }} />
          </div>
          <div className="vskin-slot vskin-slot-mp" title={`MP: ${vitals.mana}/${vitals.maxMana}`}>
            <span className="vskin-drain" style={{ left: `${mpPct}%` }} />
          </div>
          <span className="vskin-num vskin-num-hp">{vitals.hp}/{vitals.maxHp}</span>
          <span className="vskin-num vskin-num-mp">{vitals.mana}/{vitals.maxMana}</span>
          <span className="vskin-num vskin-num-gold">{vitals.gold.toLocaleString()}</span>
          <button
            type="button"
            className="vskin-hotspot vskin-heal"
            onClick={() => onCommand("quickheal")}
            disabled={hpDisabled}
            title={hpTitle}
            aria-label={hpTitle}
          />
          <button
            type="button"
            className="vskin-hotspot vskin-mana"
            onClick={() => onCommand("quickmana")}
            disabled={manaDisabled}
            title={manaTitle}
            aria-label={manaTitle}
          />
          <div className="vskin-gear">
            <AudioControls audio={audio} skinned />
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="canvas-vitals-hud">
      {/* Flowering sprig growing from the branch (the vitals bar). */}
      <svg className="vitals-sprig" viewBox="0 0 40 44" aria-hidden="true">
        <path d="M20 44 C 16 30 22 20 18 7" stroke="#6f8a3a" strokeWidth="2.4" fill="none" strokeLinecap="round" />
        <path d="M18 31 C 7 27 6 18 16 20 C 20 24 20 29 18 31 Z" fill="#7fa03e" />
        <path d="M20 23 C 31 19 32 10 22 12 C 18 16 18 21 20 23 Z" fill="#8fae4c" />
        <g transform="translate(18 6)">
          <circle cx="0" cy="-4" r="3" fill="#e7b6d2" />
          <circle cx="4" cy="-1" r="3" fill="#e7b6d2" />
          <circle cx="2" cy="3" r="3" fill="#e7b6d2" />
          <circle cx="-2" cy="3" r="3" fill="#e7b6d2" />
          <circle cx="-4" cy="-1" r="3" fill="#e7b6d2" />
          <circle cx="0" cy="0" r="2.3" fill="#f0cf72" />
        </g>
      </svg>
      <div className="vbar-vital" title={`HP: ${vitals.hp}/${vitals.maxHp}`}>
        <span className="vbar-vital-label">HP</span>
        <div className="vbar-vital-track">
          <span className="vbar-vital-fill vbar-vital-hp" style={{ width: `${percent(vitals.hp, vitals.maxHp)}%` }} />
        </div>
        <span className="vbar-vital-text">{vitals.hp}/{vitals.maxHp}</span>
      </div>
      <button
        type="button"
        className="vbar-quick-potion vbar-quick-potion-hp"
        onClick={() => onCommand("quickheal")}
        disabled={hpDisabled}
        title={hpTitle}
        aria-label={hpTitle}
      >
        <span className="vbar-quick-potion-icon" aria-hidden="true">+</span>
        <span className="vbar-quick-potion-label">Heal</span>
      </button>
      <div className="vbar-vital" title={`MP: ${vitals.mana}/${vitals.maxMana}`}>
        <span className="vbar-vital-label">MP</span>
        <div className="vbar-vital-track">
          <span className="vbar-vital-fill vbar-vital-mp" style={{ width: `${percent(vitals.mana, vitals.maxMana)}%` }} />
        </div>
        <span className="vbar-vital-text">{vitals.mana}/{vitals.maxMana}</span>
      </div>
      <button
        type="button"
        className="vbar-quick-potion vbar-quick-potion-mana"
        onClick={() => onCommand("quickmana")}
        disabled={manaDisabled}
        title={manaTitle}
        aria-label={manaTitle}
      >
        <span className="vbar-quick-potion-icon" aria-hidden="true">+</span>
        <span className="vbar-quick-potion-label">Mana</span>
      </button>
      <div className="vbar-gold">
        <span className="vbar-gold-coin" />
        <span className="vbar-gold-text">{vitals.gold.toLocaleString()}</span>
      </div>
      <AudioControls audio={audio} />
    </div>
  );
}
