import { useState } from "react";
import type { RoomState } from "../types";
import { RoomExitsCompass } from "./RoomExitsCompass";

interface RoomPanelProps {
  room: RoomState;
  /** Sorted [direction, targetRoomId] pairs. */
  exits: Array<[string, string]>;
  serverAssets: Record<string, string>;
  loggedIn: boolean;
  onCommand: (cmd: string) => void;
}

/**
 * Room description panel between the canvas and the bottom of the screen.
 * Left column carries the (scrolling) description; the right column holds the
 * compass exit pad. Fixed height so it doesn't jump between rooms. Collapsible.
 */
export function RoomPanel({ room, exits, serverAssets, loggedIn, onCommand }: RoomPanelProps) {
  const [collapsed, setCollapsed] = useState(false);

  if (!loggedIn || room.title === "-") return null;

  const bg = serverAssets["room_panel_bg"];
  const style = bg ? { ["--room-bg" as string]: `url("${bg}")` } : undefined;

  return (
    <section
      className={`room-panel${collapsed ? " room-panel-collapsed" : ""}`}
      style={style}
      aria-label="Room details"
    >
      {!collapsed && (
        <div className="room-panel-body">
          <div className="room-panel-main">
            <p className="room-panel-desc">
              {room.description || "No room description available yet."}
            </p>
          </div>

          <div className="room-panel-nav">
            <RoomExitsCompass exits={exits} serverAssets={serverAssets} onCommand={onCommand} />
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
