import type { ReactNode, RefObject } from "react";
import type { CommandEntry, ContainerContents, CraftingNode, PopoutPanel, RecallState, RoomFeature, RoomState } from "../types";
import { HelpContent } from "./HelpContent";

const PANEL_POPOUTS = new Set<string>(["character", "chat", "shop", "spellbook", "quests", "inventory", "equipment", "mail", "crafting", "housing", "trainer", "leaderboard", "bank", "auction"]);

interface PopoutLayerProps {
  activePopout: PopoutPanel;
  popoutTitle: string;
  room: RoomState;
  exits: Array<[string, string]>;
  roomFeatures: RoomFeature[];
  containerContents: ContainerContents | null;
  mapCanvasRef: RefObject<HTMLCanvasElement | null>;
  questMarkerCount: number;
  isStaff: boolean;
  serverCommands: CommandEntry[];
  craftingNodes: CraftingNode[];
  recallState?: RecallState | null;
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
  questMarkerCount,
  isStaff,
  serverCommands,
  craftingNodes,
  recallState,
  onClose,
  onFeatureAction,
  children,
}: PopoutLayerProps) {
  if (!activePopout) return null;

  const isPanelPopout = PANEL_POPOUTS.has(activePopout);
  const dialogClass = isPanelPopout
    ? `popout-dialog popout-dialog-panel popout-dialog-panel-${activePopout}`
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
          <button type="button" className="soft-button popout-close" aria-label={`Close ${popoutTitle}`} onClick={onClose}>
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
              role="img"
              aria-label={questMarkerCount > 0 ? `Visited room map — ${questMarkerCount} quest objective${questMarkerCount !== 1 ? "s" : ""} marked` : "Visited room map"}
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
              <div className="room-popout-exits">
                {exits.length === 0
                  ? <span>No exits listed.</span>
                  : <>
                      <span className="room-popout-exits-label">Exits:</span>
                      <span className="room-popout-exits-buttons">
                        {exits.map(([direction]) => (
                          <button
                            key={direction}
                            type="button"
                            className="room-exit-btn"
                            title="Click to move, Shift+Click to peek"
                            aria-label={`Move ${direction}`}
                            onClick={(e) => {
                              if (e.shiftKey) {
                                onFeatureAction(`look ${direction}`);
                              } else {
                                onFeatureAction(direction);
                              }
                            }}
                          >
                            {direction}
                          </button>
                        ))}
                      </span>
                    </>}
              </div>
              {(room.station || craftingNodes.length > 0) && (
                <div className="room-resources-section">
                  {room.station && (
                    <p className="room-resource-line">
                      <span className="room-resource-icon" aria-hidden="true">{"\u2692"}</span>
                      Crafting station: <strong>{room.station.split("_").map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ")}</strong>
                    </p>
                  )}
                  {craftingNodes.length > 0 && (
                    <div className="room-resource-nodes">
                      <span className="room-resource-icon" aria-hidden="true">{"\uD83C\uDF3F"}</span>
                      <span>Resources: </span>
                      {craftingNodes.map((node) => (
                        <button
                          key={node.id}
                          type="button"
                          className="room-resource-btn"
                          title={`Gather ${node.name} (${node.skill} ${node.skillRequired}+)`}
                          aria-label={`Gather ${node.name}`}
                          onClick={() => onFeatureAction(`gather ${node.name}`)}
                        >
                          {node.name}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}
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
                            <button className="room-feature-btn" aria-label={`Open ${f.name}`} onClick={() => onFeatureAction(`open ${f.keyword}`)}>Open</button>
                          )}
                          {(f.type === "door" || f.type === "container") && f.state === "open" && (
                            <button className="room-feature-btn" aria-label={`${f.type === "container" ? "Search" : "Close"} ${f.name}`} onClick={() => onFeatureAction(f.type === "container" ? `search ${f.keyword}` : `close ${f.keyword}`)}>
                              {f.type === "container" ? "Search" : "Close"}
                            </button>
                          )}
                          {f.type === "container" && f.state === "open" && (
                            <button className="room-feature-btn" aria-label={`Close ${f.name}`} onClick={() => onFeatureAction(`close ${f.keyword}`)}>Close</button>
                          )}
                          {(f.type === "door" || f.type === "container") && f.state === "closed" && f.locked === false && (
                            <button className="room-feature-btn" aria-label={`Lock ${f.name}`} onClick={() => onFeatureAction(`lock ${f.keyword}`)}>Lock</button>
                          )}
                          {(f.type === "door" || f.type === "container") && f.state === "locked" && (
                            <button className="room-feature-btn" aria-label={`Unlock ${f.name}`} onClick={() => onFeatureAction(`unlock ${f.keyword}`)}>Unlock</button>
                          )}
                          {f.type === "lever" && (
                            <button className="room-feature-btn" aria-label={`Pull ${f.name}`} onClick={() => onFeatureAction(`pull ${f.keyword}`)}>Pull</button>
                          )}
                          {f.type === "sign" && (
                            <button className="room-feature-btn" aria-label={`Read ${f.name}`} onClick={() => onFeatureAction(`read ${f.keyword}`)}>Read</button>
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
            <HelpContent isStaff={isStaff} serverCommands={serverCommands} />
          </div>
        )}

        {activePopout === "inn" && (
          <div className="popout-content">
            <article className="inn-popout">
              <p className="inn-popout-intro">
                You are at <strong>{room.title}</strong>. The innkeeper offers safe lodging — rest here to set your recall point.
              </p>
              <div className="inn-popout-recall">
                <span className="inn-popout-recall-label">Current recall point:</span>
                <strong className="inn-popout-recall-value">
                  {recallState?.roomTitle ?? "Not yet set"}
                </strong>
              </div>
              <button
                type="button"
                className="soft-button inn-popout-action"
                onClick={() => {
                  onFeatureAction("rest");
                  onClose();
                }}
              >
                Set Recall Here
              </button>
            </article>
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
