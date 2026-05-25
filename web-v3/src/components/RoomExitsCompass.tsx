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
      <circle cx="50" cy="50" r="40" fill="none" stroke="rgb(190 168 115 / 40%)" strokeWidth="1.5" />
      <circle cx="50" cy="50" r="30" fill="none" stroke="rgb(190 168 115 / 22%)" strokeWidth="1" />
      <polygon points="50,4 57,50 50,50 43,50" fill="rgb(212 198 150 / 92%)" />
      <polygon points="50,96 57,50 50,50 43,50" fill="rgb(150 138 100 / 85%)" />
      <polygon points="96,50 50,57 50,50 50,43" fill="rgb(190 168 115 / 90%)" />
      <polygon points="4,50 50,57 50,50 50,43" fill="rgb(190 168 115 / 90%)" />
      <polygon points="78,22 54,54 50,50 46,46" fill="rgb(120 110 80 / 70%)" />
      <polygon points="22,78 46,46 50,50 54,54" fill="rgb(120 110 80 / 70%)" />
      <polygon points="78,78 46,54 50,50 54,46" fill="rgb(120 110 80 / 70%)" />
      <polygon points="22,22 54,46 50,50 46,54" fill="rgb(120 110 80 / 70%)" />
      <circle cx="50" cy="50" r="4.5" fill="rgb(212 198 150)" />
    </svg>
  );
}

const DIRECTIONS: Array<{ dir: string; cls: string; label: string; asset: string }> = [
  { dir: "north", cls: "compass-dir-north", label: "N", asset: "compass_north" },
  { dir: "up", cls: "compass-dir-up", label: "Up", asset: "compass_up" },
  { dir: "west", cls: "compass-dir-west", label: "W", asset: "compass_west" },
  { dir: "east", cls: "compass-dir-east", label: "E", asset: "compass_east" },
  { dir: "down", cls: "compass-dir-down", label: "Dn", asset: "compass_down" },
  { dir: "south", cls: "compass-dir-south", label: "S", asset: "compass_south" },
];

/**
 * Compass-rose exit pad — a constant-size square. The rose fills the whole pad;
 * each direction button is a custom asset (falling back to a letter). An
 * existing exit is bright + clickable; a missing one is greyed and disabled.
 * Up sits in the NE corner, Down in the SW corner. Click moves; Shift+Click peeks.
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
        {DIRECTIONS.map(({ dir, cls, label, asset }) => {
          const active = present.has(dir);
          const art = serverAssets[asset];
          return (
            <button
              key={dir}
              type="button"
              className={`compass-btn ${cls}${art ? " compass-btn-art" : ""}${active ? "" : " compass-btn-off"}`}
              disabled={!active}
              title={active ? `Go ${dir} (Shift+Click to peek)` : `No ${dir} exit`}
              aria-label={active ? `Go ${dir}` : `No ${dir} exit`}
              onClick={active ? go(dir) : undefined}
            >
              {art ? <img src={art} alt={label} className="compass-btn-img" /> : label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
