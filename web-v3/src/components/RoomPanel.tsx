import { useState } from "react";
import type { PopoutPanel, RoomState } from "../types";
import { ExpandRoomIcon } from "./Icons";

interface RoomPanelProps {
  room: RoomState;
  /** Sorted [direction, targetRoomId] pairs. */
  exits: Array<[string, string]>;
  loggedIn: boolean;
  onCommand: (cmd: string) => void;
  onOpenPanel: (panel: PopoutPanel) => void;
}

function capitalize(word: string): string {
  return word.charAt(0).toUpperCase() + word.slice(1);
}

/**
 * Room description panel between the canvas and the bottom of the screen.
 * Left column carries the title + description; the right column lists the
 * room exits. Collapsible so it can fold away on small screens.
 */
export function RoomPanel({ room, exits, loggedIn, onCommand, onOpenPanel }: RoomPanelProps) {
  const [collapsed, setCollapsed] = useState(false);

  if (!loggedIn || room.title === "-") return null;

  return (
    <section className={`room-panel${collapsed ? " room-panel-collapsed" : ""}`} aria-label="Room details">
      {!collapsed && (
        <div className="room-panel-body">
          <div className="room-panel-main">
            <header className="room-panel-header">
              <h2 className="room-panel-title">{room.title}</h2>
              <button
                type="button"
                className="room-panel-expand"
                aria-label="Expand room details"
                title="Expand room details"
                onClick={() => onOpenPanel("room")}
              >
                <ExpandRoomIcon className="room-panel-expand-icon" />
              </button>
            </header>
            <p className="room-panel-desc">
              {room.description || "No room description available yet."}
            </p>
          </div>

          <div className="room-panel-nav">
            <div className="room-panel-nav-group">
              <h3 className="room-panel-nav-heading">Room Exits</h3>
              {exits.length === 0 ? (
                <p className="room-panel-nav-empty">No visible exits.</p>
              ) : (
                exits.map(([direction]) => (
                  <button
                    key={direction}
                    type="button"
                    className="room-panel-exit"
                    title={`Move ${direction} (Shift+Click to peek)`}
                    onClick={(e) => onCommand(e.shiftKey ? `look ${direction}` : direction)}
                  >
                    <span className="room-panel-exit-label">Exit {capitalize(direction)}</span>
                    <span className="room-panel-exit-arrow" aria-hidden="true">{"→"}</span>
                  </button>
                ))
              )}
            </div>
          </div>
        </div>
      )}

      <button
        type="button"
        className="room-panel-toggle"
        aria-label={collapsed ? "Show room details" : "Hide room details"}
        aria-expanded={!collapsed}
        onClick={() => setCollapsed((c) => !c)}
      >
        <svg viewBox="0 0 24 24" className={`room-panel-toggle-icon${collapsed ? " room-panel-toggle-icon-up" : ""}`} fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M6 9l6 6 6-6" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
    </section>
  );
}
