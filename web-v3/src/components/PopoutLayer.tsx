import type { RefObject } from "react";
import type { ItemSummary, PopoutPanel, RoomState } from "../types";

interface PopoutLayerProps {
  activePopout: PopoutPanel;
  popoutTitle: string;
  room: RoomState;
  exits: Array<[string, string]>;
  inventory: ItemSummary[];
  equipment: Record<string, ItemSummary>;
  equipmentSlots: string[];
  mapCanvasRef: RefObject<HTMLCanvasElement | null>;
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
                  <li key={item.id}>{item.name}</li>
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
                    <span>{equipment[slot]?.name ?? "Unknown"}</span>
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

