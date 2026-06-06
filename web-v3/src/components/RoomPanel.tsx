import { useState } from "react";
import type { RoomState } from "../types";
import { PeekSentence } from "./PeekSentence";
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

  return (
    <section
      className={`room-panel${collapsed ? " room-panel-collapsed" : ""}`}
      aria-label="Room details"
    >
      {!collapsed && (
        <div className="room-panel-body">
          <div className="room-panel-main">
            {/* Echo the room name here too (also on the hanging sign) for
                cohesion. Hidden on narrow screens where vertical room is tight. */}
            <h2 className="room-panel-title">{room.title}</h2>
            <p className="room-panel-desc">
              {room.description || "No room description available yet."}
            </p>
            {room.peek && room.peek.length > 0 && (
              <p className="room-panel-peek"><PeekSentence peek={room.peek} /></p>
            )}
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
