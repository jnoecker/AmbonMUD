import type { ReactNode, RefObject } from "react";
import type { ContainerContents, PopoutPanel, RoomFeature, RoomState } from "../types";
import { HelpContent } from "./HelpContent";

const PANEL_POPOUTS = new Set<string>(["character", "chat", "shop", "spellbook", "quests", "inventory", "equipment", "mail", "crafting"]);

interface PopoutLayerProps {
  activePopout: PopoutPanel;
  popoutTitle: string;
  room: RoomState;
  exits: Array<[string, string]>;
  roomFeatures: RoomFeature[];
  containerContents: ContainerContents | null;
  mapCanvasRef: RefObject<HTMLCanvasElement | null>;
  isStaff: boolean;
  onClose: () => void;
  onFeatureAction: (command: string) => void;
  children?: ReactNode;
}

export function PopoutLayer({
  activePopout,
  popoutTitle,
  room,
  exits,
  roomFeatures,
  containerContents,
  mapCanvasRef,
  isStaff,
  onClose,
  onFeatureAction,
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
              {roomFeatures.length > 0 && (
                <div className="room-features-section">
                  <h4 className="room-features-title">Interactive Features</h4>
                  <ul className="room-features-list" role="list">
                    {roomFeatures.map((f) => (
                      <li key={f.id} className="room-feature-item">
                        <span className="room-feature-name">{f.name}</span>
                        {f.state && <span className="room-feature-state">({f.state})</span>}
                        <span className="room-feature-actions">
                          {(f.type === "door" || f.type === "container") && f.state === "closed" && (
                            <button className="room-feature-btn" onClick={() => onFeatureAction(`open ${f.keyword}`)}>Open</button>
                          )}
                          {(f.type === "door" || f.type === "container") && f.state === "open" && (
                            <button className="room-feature-btn" onClick={() => onFeatureAction(f.type === "container" ? `search ${f.keyword}` : `close ${f.keyword}`)}>
                              {f.type === "container" ? "Search" : "Close"}
                            </button>
                          )}
                          {f.type === "container" && f.state === "open" && (
                            <button className="room-feature-btn" onClick={() => onFeatureAction(`close ${f.keyword}`)}>Close</button>
                          )}
                          {(f.type === "door" || f.type === "container") && f.state === "locked" && (
                            <button className="room-feature-btn" onClick={() => onFeatureAction(`unlock ${f.keyword}`)}>Unlock</button>
                          )}
                          {f.type === "lever" && (
                            <button className="room-feature-btn" onClick={() => onFeatureAction(`pull ${f.keyword}`)}>Pull</button>
                          )}
                          {f.type === "sign" && (
                            <button className="room-feature-btn" onClick={() => onFeatureAction(`read ${f.keyword}`)}>Read</button>
                          )}
                        </span>
                        {f.type === "sign" && f.text && (
                          <p className="room-sign-text">{f.text}</p>
                        )}
                      </li>
                    ))}
                  </ul>
                  {containerContents && (
                    <div className="container-contents-section">
                      <h4 className="room-features-title">In the {containerContents.name}</h4>
                      {containerContents.items.length === 0 ? (
                        <p className="empty-note">Empty.</p>
                      ) : (
                        <ul className="container-contents-list" role="list">
                          {containerContents.items.map((item) => (
                            <li key={item.keyword} className="container-contents-item">
                              <span>{item.name}</span>
                              <button className="room-feature-btn" onClick={() => onFeatureAction(`get ${item.keyword} from ${containerContents.keyword}`)}>Take</button>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  )}
                </div>
              )}
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
