import type { ReactNode } from "react";
import type { PopoutPanel } from "../types";
import {
  CharacterAvatarIcon,
  EquipmentIcon,
  WearingIcon,
  SpellbookIcon,
  QuestsTabIcon,
  AttackIcon,
} from "./Icons";

interface KioskDef {
  panel: PopoutPanel;
  label: string;
  /** Server asset key for the kiosk artwork. */
  assetKey: string;
  fallback: ReactNode;
}

const KIOSKS: KioskDef[] = [
  { panel: "character", label: "Character", assetKey: "character_widget", fallback: <CharacterAvatarIcon className="kiosk-icon-svg" /> },
  { panel: "inventory", label: "Inventory", assetKey: "inventory_widget", fallback: <EquipmentIcon className="kiosk-icon-svg" /> },
  { panel: "equipment", label: "Equipment", assetKey: "equipment_widget", fallback: <WearingIcon className="kiosk-icon-svg" /> },
  { panel: "spellbook", label: "Spellbook", assetKey: "spellbook_widget", fallback: <SpellbookIcon className="kiosk-icon-svg" /> },
  { panel: "quests", label: "Quests", assetKey: "quests_widget", fallback: <QuestsTabIcon className="kiosk-icon-svg" /> },
  { panel: "combatlog", label: "Combat Log", assetKey: "combat_log_widget", fallback: <AttackIcon className="kiosk-icon-svg" /> },
];

interface KioskBarProps {
  serverAssets: Record<string, string>;
  activePopout: PopoutPanel;
  onOpenPanel: (panel: PopoutPanel) => void;
}

/**
 * Panel kiosk row in the action dock (out of combat). Context services
 * (Auction, Mail) are handled by the in-world Pixi room badges instead, so they
 * stack with Shop/Inn and never overlap.
 */
export function KioskBar({ serverAssets, activePopout, onOpenPanel }: KioskBarProps) {
  return (
    <nav className="kiosks" aria-label="Panels">
      {KIOSKS.map((def) => {
        const art = serverAssets[def.assetKey];
        return (
          <button
            key={def.panel}
            type="button"
            className={`kiosk${activePopout === def.panel ? " kiosk-active" : ""}`}
            onClick={() => onOpenPanel(def.panel)}
            title={def.label}
            aria-label={def.label}
            aria-pressed={activePopout === def.panel}
          >
            <span className="kiosk-icon">
              {art ? <img src={art} alt="" aria-hidden="true" className="kiosk-img" /> : def.fallback}
            </span>
            <span className="kiosk-label">{def.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
