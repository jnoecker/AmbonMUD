import type { MouseEvent } from "react";

interface RoomExitsCompassProps {
  /** Sorted [direction, targetRoomId] pairs for the current room. */
  exits: Array<[string, string]>;
  serverAssets: Record<string, string>;
  onCommand: (cmd: string) => void;
}

/** Built-in compass rose drawn when no `compass_widget` art is supplied. */
function CompassRose() {
  return (
    <svg viewBox="0 0 100 100" className="room-compass-rose-svg" aria-hidden="true">
      <circle cx="50" cy="50" r="34" fill="none" stroke="rgb(190 168 115 / 45%)" strokeWidth="1.5" />
      <circle cx="50" cy="50" r="26" fill="none" stroke="rgb(190 168 115 / 25%)" strokeWidth="1" />
      <polygon points="50,8 56,50 50,50 44,50" fill="rgb(212 198 150 / 92%)" />
      <polygon points="50,92 56,50 50,50 44,50" fill="rgb(150 138 100 / 85%)" />
      <polygon points="92,50 50,56 50,50 50,44" fill="rgb(190 168 115 / 90%)" />
      <polygon points="8,50 50,56 50,50 50,44" fill="rgb(190 168 115 / 90%)" />
      <polygon points="74,26 53,53 50,50 47,47" fill="rgb(120 110 80 / 70%)" />
      <polygon points="26,74 47,47 50,50 53,53" fill="rgb(120 110 80 / 70%)" />
      <polygon points="74,74 47,53 50,50 53,47" fill="rgb(120 110 80 / 70%)" />
      <polygon points="26,26 53,47 50,50 47,53" fill="rgb(120 110 80 / 70%)" />
      <circle cx="50" cy="50" r="4" fill="rgb(212 198 150)" />
    </svg>
  );
}

const DIRECTIONS: Array<{ dir: string; cls: string; label: string }> = [
  { dir: "north", cls: "compass-dir-north", label: "N" },
  { dir: "up", cls: "compass-dir-up", label: "Up" },
  { dir: "west", cls: "compass-dir-west", label: "W" },
  { dir: "east", cls: "compass-dir-east", label: "E" },
  { dir: "down", cls: "compass-dir-down", label: "Dn" },
  { dir: "south", cls: "compass-dir-south", label: "S" },
];

/**
 * Compass-rose exit pad — a constant-size square. Every direction is always
 * shown: an existing exit is an outlined, clickable box; a missing one is a
 * greyed-out, disabled label. Up sits in the NE corner, Down in the SW corner.
 * Click moves; Shift+Click peeks.
 */
export function RoomExitsCompass({ exits, serverAssets, onCommand }: RoomExitsCompassProps) {
  const present = new Set(exits.map(([dir]) => dir));
  const go = (dir: string) => (e: MouseEvent) => onCommand(e.shiftKey ? `look ${dir}` : dir);
  const rose = serverAssets["compass_widget"];

  return (
    <div className="room-compass" aria-label="Exits">
      <div className="room-compass-pad">
        <div className="room-compass-rose">
          {rose ? <img src={rose} alt="" className="room-compass-rose-img" /> : <CompassRose />}
        </div>
        {DIRECTIONS.map(({ dir, cls, label }) => {
          const active = present.has(dir);
          return (
            <button
              key={dir}
              type="button"
              className={`compass-btn ${cls}${active ? "" : " compass-btn-off"}`}
              disabled={!active}
              title={active ? `Go ${dir} (Shift+Click to peek)` : `No ${dir} exit`}
              aria-label={active ? `Go ${dir}` : `No ${dir} exit`}
              onClick={active ? go(dir) : undefined}
            >
              {label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
