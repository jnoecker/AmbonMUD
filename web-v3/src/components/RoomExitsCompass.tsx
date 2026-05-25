import type { MouseEvent } from "react";

interface RoomExitsCompassProps {
  /** Sorted [direction, targetRoomId] pairs for the current room. */
  exits: Array<[string, string]>;
  serverAssets: Record<string, string>;
  onCommand: (cmd: string) => void;
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
 * Compass exit pad — a constant-size square whose rose/frame come from the
 * compass_bg backdrop. Each direction button is a custom asset (falling back to
 * a letter): an existing exit is bright + clickable, a missing one is greyed and
 * disabled. Up sits in the NE corner, Down in the SW corner. Click moves;
 * Shift+Click peeks.
 */
export function RoomExitsCompass({ exits, serverAssets, onCommand }: RoomExitsCompassProps) {
  const present = new Set(exits.map(([dir]) => dir));
  const go = (dir: string) => (e: MouseEvent) => onCommand(e.shiftKey ? `look ${dir}` : dir);
  const bg = serverAssets["compass_bg"];
  const padStyle = bg ? { ["--compass-bg" as string]: `url("${bg}")` } : undefined;

  return (
    <div className="room-compass" aria-label="Exits">
      <div className="room-compass-pad" style={padStyle}>
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
