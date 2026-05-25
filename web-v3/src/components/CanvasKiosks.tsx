import type { ReactNode } from "react";
import type { PopoutPanel, RoomState } from "../types";
import {
  EquipmentIcon,
  WearingIcon,
  SpellbookIcon,
  QuestsTabIcon,
  AttackIcon,
  AuctionIcon,
  MailIcon,
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

const AUCTION_KIOSK: KioskDef = { panel: "auction", label: "Auction", assetKey: "auction_widget", fallback: <AuctionIcon className="kiosk-icon-svg" /> };
const MAIL_KIOSK: KioskDef = { panel: "mail", label: "Mail", assetKey: "mail_widget", fallback: <MailIcon className="kiosk-icon-svg" /> };

interface CanvasKiosksProps {
  serverAssets: Record<string, string>;
  activePopout: PopoutPanel;
  room: RoomState;
  onOpenPanel: (panel: PopoutPanel) => void;
}

function Kiosk({ def, serverAssets, active, onOpenPanel }: {
  def: KioskDef;
  serverAssets: Record<string, string>;
  active: boolean;
  onOpenPanel: (panel: PopoutPanel) => void;
}) {
  const art = serverAssets[def.assetKey];
  return (
    <button
      type="button"
      className={`canvas-kiosk${active ? " canvas-kiosk-active" : ""}`}
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
}

/**
 * Kiosk buttons overlaid on the canvas edges. The left column is always shown;
 * the right column holds context kiosks that only appear in the relevant rooms
 * (Auction in auction houses, Mail in inns / player homes).
 */
export function CanvasKiosks({ serverAssets, activePopout, room, onOpenPanel }: CanvasKiosksProps) {
  const showAuction = room.auction === true;
  const showMail = room.inn === true || room.housing === true;
  const rightKiosks: KioskDef[] = [];
  if (showAuction) rightKiosks.push(AUCTION_KIOSK);
  if (showMail) rightKiosks.push(MAIL_KIOSK);

  return (
    <>
      <nav className="canvas-kiosks canvas-kiosks-left" aria-label="Panels">
        {LEFT_KIOSKS.map((def) => (
          <Kiosk
            key={def.panel}
            def={def}
            serverAssets={serverAssets}
            active={activePopout === def.panel}
            onOpenPanel={onOpenPanel}
          />
        ))}
      </nav>

      {rightKiosks.length > 0 && (
        <nav className="canvas-kiosks canvas-kiosks-right" aria-label="Location services">
          {rightKiosks.map((def) => (
            <Kiosk
              key={def.panel}
              def={def}
              serverAssets={serverAssets}
              active={activePopout === def.panel}
              onOpenPanel={onOpenPanel}
            />
          ))}
        </nav>
      )}
    </>
  );
}
