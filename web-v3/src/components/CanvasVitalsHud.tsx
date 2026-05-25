import type { ItemSummary, Vitals } from "../types";
import type { AudioEngine } from "../hooks/useAudioEngine";
import { AudioControls } from "./AudioControls";
import { selectQuickPotion } from "../combat/quickPotion";
import { percent } from "../utils";

interface CanvasVitalsHudProps {
  vitals: Vitals;
  inventory: ItemSummary[];
  onCommand: (cmd: string) => void;
  audio: AudioEngine;
}

/**
 * Horizontal HP / MP / Gold strip across the top-left of the canvas. Laid out
 * in a single row so it claims minimal vertical space and frees the left edge
 * for the kiosk column.
 */
export function CanvasVitalsHud({ vitals, inventory, onCommand, audio }: CanvasVitalsHudProps) {
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

  return (
    <div className="canvas-vitals-hud">
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
