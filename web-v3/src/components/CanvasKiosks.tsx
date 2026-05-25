import type { ReactNode } from "react";
import type { PopoutPanel } from "../types";
import {
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

const LEFT_KIOSKS: KioskDef[] = [
  { panel: "inventory", label: "Inventory", assetKey: "inventory_widget", fallback: <EquipmentIcon className="kiosk-icon-svg" /> },
  { panel: "equipment", label: "Equipment", assetKey: "equipment_widget", fallback: <WearingIcon className="kiosk-icon-svg" /> },
  { panel: "spellbook", label: "Spellbook", assetKey: "spellbook_widget", fallback: <SpellbookIcon className="kiosk-icon-svg" /> },
  { panel: "quests", label: "Quests", assetKey: "quests_widget", fallback: <QuestsTabIcon className="kiosk-icon-svg" /> },
  { panel: "combatlog", label: "Combat Log", assetKey: "combat_log_widget", fallback: <AttackIcon className="kiosk-icon-svg" /> },
];

interface CanvasKiosksProps {
  serverAssets: Record<string, string>;
  activePopout: PopoutPanel;
  onOpenPanel: (panel: PopoutPanel) => void;
}

/**
 * Persistent panel kiosks down the left edge of the canvas. Context services
 * (Auction, Mail) are handled by the in-world Pixi room badges instead, so they
 * stack with Shop/Inn and never overlap.
 */
export function CanvasKiosks({ serverAssets, activePopout, onOpenPanel }: CanvasKiosksProps) {
  return (
    <nav className="canvas-kiosks canvas-kiosks-left" aria-label="Panels">
      {LEFT_KIOSKS.map((def) => {
        const art = serverAssets[def.assetKey];
        return (
          <button
            key={def.panel}
            type="button"
            className={`canvas-kiosk${activePopout === def.panel ? " canvas-kiosk-active" : ""}`}
            onClick={() => onOpenPanel(def.panel)}
            title={def.label}
            aria-label={def.label}
          >
            <span className="canvas-kiosk-icon">
              {art ? <img src={art} alt="" className="canvas-kiosk-img" /> : def.fallback}
            </span>
            <span className="canvas-kiosk-label">{def.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
