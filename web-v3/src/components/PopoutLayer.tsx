import type { RefObject } from "react";
import type { ItemSummary, PopoutPanel, RoomState } from "../types";
import { DropItemIcon, RemoveItemIcon, WearItemIcon } from "./Icons";

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
  onWearItem: (itemName: string) => void;
  onDropItem: (itemName: string) => void;
  onRemoveItem: (slot: string) => void;
  onClose: () => void;
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
  onWearItem,
  onDropItem,
  onRemoveItem,
  onClose,
}: PopoutLayerProps) {
  if (!activePopout) return null;

  return (
    <div className="popout-backdrop" onClick={onClose}>
      <section
        className="popout-dialog"
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
                  <li key={item.id}>
                    <span>{item.name}</span>
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
                        <span>{equipment[slot]?.name ?? "Unknown"}</span>
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
      </section>
    </div>
  );
}

