import { useState, type CSSProperties } from "react";
import type { BoatDestination, BoatState } from "../../types";

interface BoatPanelProps {
  boatState: BoatState | null;
  /** Resolved Server.Assets map — supplies the painted Ambon map (`boat_map`) and anchor marker (`boat_dock`). */
  serverAssets: Record<string, string>;
  onCommand: (cmd: string) => void;
}

/**
 * One textual route row — used for the fallback list (no map art) and for the
 * "other ports" list of destinations the worldbuilder hasn't pinned to the map yet.
 */
function RouteRow({
  dest,
  onCommand,
}: {
  dest: BoatDestination;
  onCommand: (cmd: string) => void;
}) {
  return (
    <li className="boat-row">
      <div className="boat-row-text">
        <span className="boat-row-name">{dest.name}</span>
        <span className="boat-row-meta">{dest.zone}</span>
      </div>
      <div className="boat-row-fare">
        <span className={`boat-cost${dest.affordable ? "" : " boat-cost-short"}`}>
          {dest.price.toLocaleString()} gold
        </span>
        <button
          type="button"
          className="boat-go"
          disabled={!dest.affordable}
          title={dest.affordable ? `Sail to ${dest.name}` : "Not enough gold"}
          aria-label={`Sail to ${dest.name} for ${dest.price} gold`}
          onClick={() => onCommand(`sail ${dest.roomId}`)}
        >
          Sail
        </button>
      </div>
    </li>
  );
}

/**
 * Boat dock — pay gold to sail one of the dock's authored routes.
 *
 * When the painted Ambon map (`boat_map`, by default the same art as the flight kiosk) is
 * available, the dock renders it full-bleed and seats each route's destination onto it as a
 * clickable anchor hotspot, positioned by the destination room's authored `boatMapX`/`boatMapY`
 * percentages. The dock you're standing at shows a "you are here" marker; affordable ports glow,
 * unaffordable ones dim. Destinations the worldbuilder hasn't pinned, plus the whole list when no
 * map art is present, fall back to the plain textual list. Clicking always sends `sail <roomId>`
 * (unambiguous), so the picked port matches the marker.
 */
export function BoatPanel({ boatState, serverAssets, onCommand }: BoatPanelProps) {
  // boat_map is always registered (defaults to the shared flight map art), so its URL is non-null
  // even before any PNG is uploaded. A failed load (404) records the offending URL and we degrade
  // to the textual list rather than stranding the player on a broken image. Comparing against the
  // current URL auto-resets the broken state if the art is swapped in later.
  const mapUrl = serverAssets["boat_map"] ?? null;
  const [brokenMapUrl, setBrokenMapUrl] = useState<string | null>(null);
  const mapBroken = brokenMapUrl !== null && brokenMapUrl === mapUrl;

  if (!boatState) {
    return (
      <div className="boat-board boat-board-empty">
        <p className="boat-empty">
          No boats are berthed here. Visit a boat dock and use the <code>voyages</code> command.
        </p>
      </div>
    );
  }

  const { playerGold, destinations, originName, originMapX, originMapY } = boatState;
  const dockUrl = serverAssets["boat_dock"] ?? null;

  const placed = destinations.filter((d) => d.mapX != null && d.mapY != null);
  const unplaced = destinations.filter((d) => d.mapX == null || d.mapY == null);
  const originPlaced = originMapX != null && originMapY != null;

  // Render the painted map only when we have (working) art and at least one thing to pin onto
  // it; otherwise the plain list below is the whole kiosk (graceful degradation).
  const showMap = mapUrl != null && !mapBroken && (placed.length > 0 || originPlaced);

  const goldLine = (
    <div className="boat-gold">
      Your Gold: <strong>{playerGold.toLocaleString()}</strong>
    </div>
  );

  if (!showMap) {
    return (
      <div className="boat-board">
        {goldLine}
        {destinations.length === 0 ? (
          <p className="boat-empty">No boats are berthed here. This dock has no routes.</p>
        ) : (
          <ul className="boat-list">
            {destinations.map((dest) => (
              <RouteRow key={dest.roomId} dest={dest} onCommand={onCommand} />
            ))}
          </ul>
        )}
      </div>
    );
  }

  const skinVars: Record<string, string> = {};
  if (dockUrl) skinVars["--boat-dock"] = `url("${dockUrl}")`;

  return (
    <div className="boat-board boat-board-skinned" style={skinVars as CSSProperties}>
      {goldLine}

      <div className="boat-map">
        <img
          className="boat-map-img"
          src={mapUrl ?? undefined}
          alt="Map of Ambon"
          draggable={false}
          onError={() => setBrokenMapUrl(mapUrl)}
        />
        {originPlaced && (
          <div
            className="boat-marker boat-marker-origin"
            style={{ left: `${originMapX}%`, top: `${originMapY}%` }}
          >
            <span className="boat-marker-dot" />
            <span className="boat-marker-tip">
              <span className="boat-tip-name">You are here</span>
              {originName && <span className="boat-tip-meta">{originName}</span>}
            </span>
          </div>
        )}

        {placed.map((dest) => (
          <button
            key={dest.roomId}
            type="button"
            className={
              `boat-marker boat-marker-dest` +
              `${dest.affordable ? "" : " boat-marker-short"}` +
              `${dockUrl ? " boat-marker-art" : ""}`
            }
            style={{ left: `${dest.mapX}%`, top: `${dest.mapY}%` }}
            aria-disabled={!dest.affordable}
            aria-label={
              dest.affordable
                ? `Sail to ${dest.name} for ${dest.price} gold`
                : `${dest.name} — not enough gold (${dest.price})`
            }
            onClick={() => {
              if (dest.affordable) onCommand(`sail ${dest.roomId}`);
            }}
          >
            <span className="boat-marker-dot" />
            <span className="boat-marker-tip">
              <span className="boat-tip-name">{dest.name}</span>
              <span className="boat-tip-meta">{dest.zone}</span>
              <span className={`boat-tip-fare${dest.affordable ? "" : " boat-cost-short"}`}>
                {dest.price.toLocaleString()} gold{dest.affordable ? "" : " — too dear"}
              </span>
            </span>
          </button>
        ))}
      </div>

      {unplaced.length > 0 && (
        <div className="boat-unplaced">
          <p className="boat-unplaced-head">Other ports</p>
          <ul className="boat-list">
            {unplaced.map((dest) => (
              <RouteRow key={dest.roomId} dest={dest} onCommand={onCommand} />
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
