import { useState } from "react";
import type { ReactNode, RefObject } from "react";
import type { ItemSummary, PopoutPanel, RoomPlayer, RoomState } from "../types";
import { HelpContent } from "./HelpContent";
import { DropItemIcon, GiveItemIcon, RemoveItemIcon, WearItemIcon } from "./Icons";

const PANEL_POPOUTS = new Set<string>(["character", "chat", "shop", "spellbook"]);

interface PopoutLayerProps {
  activePopout: PopoutPanel;
  popoutTitle: string;
  room: RoomState;
  exits: Array<[string, string]>;
  inventory: ItemSummary[];
  equipment: Record<string, ItemSummary>;
  equipmentSlots: string[];
  mapCanvasRef: RefObject<HTMLCanvasElement | null>;
  canManageItems: boolean;
  players: RoomPlayer[];
  isStaff: boolean;
  onWearItem: (itemName: string) => void;
  onDropItem: (itemName: string) => void;
  onRemoveItem: (slot: string) => void;
  onGiveItem: (itemKeyword: string, playerName: string) => void;
  onClose: () => void;
  children?: ReactNode;
}

export function PopoutLayer({
  activePopout,
  popoutTitle,
  room,
  exits,
  inventory,
  equipment,
  equipmentSlots,
  mapCanvasRef,
  canManageItems,
  players,
  isStaff,
  onWearItem,
  onDropItem,
  onRemoveItem,
  onGiveItem,
  onClose,
  children,
}: PopoutLayerProps) {
  const [givePickerItemId, setGivePickerItemId] = useState<string | null>(null);
  if (!activePopout) return null;

  const isPanelPopout = PANEL_POPOUTS.has(activePopout);
  const dialogClass = isPanelPopout
    ? "popout-dialog popout-dialog-panel"
    : "popout-dialog";

  return (
    <div className="popout-backdrop" onClick={onClose}>
      <section
        className={dialogClass}
        role="dialog"
        aria-modal="true"
        aria-label={popoutTitle}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="popout-header">
          <h2>{popoutTitle}</h2>
          <button type="button" className="soft-button popout-close" onClick={onClose}>
            Close
          </button>
        </header>

        {activePopout === "map" && (
          <div className="popout-content">
            <canvas
              ref={mapCanvasRef}
              className="mini-map mini-map-popout"
              width={900}
              height={560}
              aria-label="Visited room map"
            />
          </div>
        )}

        {activePopout === "room" && (
          <div className="popout-content">
            <article className="room-popout-copy">
              <h3 className="room-popout-title">{room.title}</h3>
              {room.image && (
                <img src={room.image} alt={room.title} className="room-popout-image" />
              )}
              <p className="room-popout-text">{room.description || "No room description available yet."}</p>
              <p className="room-popout-exits">
                {exits.length === 0
                  ? "No exits listed."
                  : `Available exits: ${exits.map(([direction]) => direction).join(", ")}`}
              </p>
            </article>
          </div>
        )}

        {activePopout === "equipment" && (
          <div className="popout-content">
            {inventory.length === 0 ? (
              <p className="empty-note">No equipment in bags right now.</p>
            ) : (
              <ul className="item-list">
                {inventory.map((item) => (
                  <li key={item.id} className="item-list-entry">
                    <div className="item-list-row">
                      <span className="entity-name-with-thumb">
                        {item.image && <img src={item.image} alt="" className="entity-thumb" />}
                        {item.name}
                      </span>
                      <span className="item-popout-actions">
                        {item.slot && (
                          <button
                            type="button"
                            className="mob-command-button"
                            title={`Wear ${item.name}`}
                            aria-label={`Wear ${item.name}`}
                            disabled={!canManageItems}
                            onClick={() => onWearItem(item.name)}
                          >
                            <WearItemIcon className="mob-command-icon" />
                          </button>
                        )}
                        {players.length > 0 && (
                          <button
                            type="button"
                            className={`mob-command-button ${givePickerItemId === item.id ? "mob-command-button-active" : ""}`}
                            title={`Give ${item.name}`}
                            aria-label={`Give ${item.name}`}
                            aria-expanded={givePickerItemId === item.id}
                            disabled={!canManageItems}
                            onClick={() => setGivePickerItemId(givePickerItemId === item.id ? null : item.id)}
                          >
                            <GiveItemIcon className="mob-command-icon" />
                          </button>
                        )}
                        <button
                          type="button"
                          className="mob-command-button"
                          title={`Drop ${item.name}`}
                          aria-label={`Drop ${item.name}`}
                          disabled={!canManageItems}
                          onClick={() => onDropItem(item.name)}
                        >
                          <DropItemIcon className="mob-command-icon" />
                        </button>
                      </span>
                    </div>
                    {givePickerItemId === item.id && (
                      <div className="give-player-picker" role="listbox" aria-label={`Give ${item.name} to`}>
                        <span className="give-picker-label">Give to:</span>
                        {players.map((player) => (
                          <button
                            key={player.name}
                            type="button"
                            role="option"
                            className="give-player-option"
                            onClick={() => {
                              onGiveItem(item.keyword, player.name);
                              setGivePickerItemId(null);
                            }}
                          >
                            {player.name}
                          </button>
                        ))}
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {activePopout === "wearing" && (
          <div className="popout-content">
            {equipmentSlots.length === 0 ? (
              <p className="empty-note">Nothing currently worn.</p>
            ) : (
                <ul className="equipment-list">
                  {equipmentSlots.map((slot) => (
                    <li key={slot}>
                      <span className="equipment-slot">{slot}</span>
                      <span className="equipment-popout-row">
                        <span className="entity-name-with-thumb">
                          {equipment[slot]?.image && <img src={equipment[slot].image!} alt="" className="entity-thumb" />}
                          {equipment[slot]?.name ?? "Unknown"}
                        </span>
                        <button
                          type="button"
                          className="mob-command-button"
                          title={`Remove ${slot}`}
                          aria-label={`Remove ${slot}`}
                          disabled={!canManageItems}
                          onClick={() => onRemoveItem(slot)}
                        >
                          <RemoveItemIcon className="mob-command-icon" />
                        </button>
                      </span>
                    </li>
                  ))}
                </ul>
            )}
          </div>
        )}

        {activePopout === "help" && (
          <div className="popout-content">
            <HelpContent isStaff={isStaff} />
          </div>
        )}

        {isPanelPopout && children && (
          <div className="popout-content popout-panel-content">
            {children}
          </div>
        )}
      </section>
    </div>
  );
}
