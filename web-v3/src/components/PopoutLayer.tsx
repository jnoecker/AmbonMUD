import type { ReactNode, RefObject } from "react";
import type { PopoutPanel, RoomState } from "../types";
import { HelpContent } from "./HelpContent";

const PANEL_POPOUTS = new Set<string>(["character", "chat", "shop", "spellbook", "quests", "inventory", "equipment"]);

interface PopoutLayerProps {
  activePopout: PopoutPanel;
  popoutTitle: string;
  room: RoomState;
  exits: Array<[string, string]>;
  mapCanvasRef: RefObject<HTMLCanvasElement | null>;
  isStaff: boolean;
  onClose: () => void;
  children?: ReactNode;
}

export function PopoutLayer({
  activePopout,
  popoutTitle,
  room,
  exits,
  mapCanvasRef,
  isStaff,
  onClose,
  children,
}: PopoutLayerProps) {
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
              {room.video && (
                <video
                  src={room.video}
                  controls
                  className="room-popout-video"
                  style={{ width: "100%", maxHeight: 300, borderRadius: 8, marginTop: 8 }}
                />
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
