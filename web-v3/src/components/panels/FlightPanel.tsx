import { useState, type CSSProperties } from "react";
import type { FlightDestination, FlightState } from "../../types";

interface FlightPanelProps {
  flightState: FlightState | null;
  /** Resolved Server.Assets map — supplies the painted Ambon map (`flight_map`) and griffin marker (`flight_roost`). */
  serverAssets: Record<string, string>;
  onCommand: (cmd: string) => void;
}

/** Human-readable travel distance for a destination. */
function distanceLabel(dist: number | null): string {
  if (dist == null) return "a distant roost";
  if (dist === 1) return "1 room away";
  return `${dist} rooms away`;
}

/**
 * One textual destination row — used for the fallback list (no map art) and for the
 * "other roosts" list of points the worldbuilder hasn't pinned to the map yet.
 */
function DestinationRow({
  dest,
  onCommand,
}: {
  dest: FlightDestination;
  onCommand: (cmd: string) => void;
}) {
  return (
    <li className="flight-row">
      <div className="flight-row-text">
        <span className="flight-row-name">{dest.name}</span>
        <span className="flight-row-meta">
          {dest.zone} · {distanceLabel(dest.distance)}
        </span>
      </div>
      <div className="flight-row-fare">
        <span className={`flight-cost${dest.affordable ? "" : " flight-cost-short"}`}>
          {dest.cost.toLocaleString()} gold
        </span>
        <button
          type="button"
          className="flight-go"
          disabled={!dest.affordable}
          title={dest.affordable ? `Fly to ${dest.name}` : "Not enough gold"}
          aria-label={`Fly to ${dest.name} for ${dest.cost} gold`}
          onClick={() => onCommand(`fly ${dest.roomId}`)}
        >
          Fly
        </button>
      </div>
    </li>
  );
}

/**
 * Flight master — pay gold to fast-travel to a flight point you've discovered.
 *
 * When the painted Ambon map (`flight_map`) is available, the kiosk renders it full-bleed and
 * seats each discovered flight point onto it as a clickable griffin hotspot, positioned by the
 * room's authored `flightMapX`/`flightMapY` percentages (the same paper-doll convention the
 * equipment panel uses). The roost you're standing at shows a "you are here" marker; affordable
 * roosts glow, unaffordable ones dim. Points the worldbuilder hasn't pinned yet, plus the whole
 * list when no map art is present, fall back to the plain textual list — nothing is unreachable.
 * Clicking always sends `fly <roomId>` (unambiguous), so the picked roost matches the marker.
 */
export function FlightPanel({ flightState, serverAssets, onCommand }: FlightPanelProps) {
  // The flight_map key is always registered, so its URL is non-null even before the painted
  // PNG is uploaded. A failed load (404) records the offending URL and we degrade to the
  // textual list rather than stranding the player on a broken image. Comparing against the
  // current URL auto-resets the broken state if the art is swapped in later.
  const mapUrl = serverAssets["flight_map"] ?? null;
  const [brokenMapUrl, setBrokenMapUrl] = useState<string | null>(null);
  const mapBroken = brokenMapUrl !== null && brokenMapUrl === mapUrl;

  if (!flightState) {
    return (
      <div className="flight-board flight-board-empty">
        <p className="flight-empty">
          No griffins are roosting here. Visit a flight master and use the <code>flights</code> command.
        </p>
      </div>
    );
  }

  const { playerGold, destinations, originName, originMapX, originMapY } = flightState;
  const roostUrl = serverAssets["flight_roost"] ?? null;

  const placed = destinations.filter((d) => d.mapX != null && d.mapY != null);
  const unplaced = destinations.filter((d) => d.mapX == null || d.mapY == null);
  const originPlaced = originMapX != null && originMapY != null;

  // Render the painted map only when we have (working) art and at least one thing to pin onto
  // it; otherwise the plain list below is the whole kiosk (graceful degradation).
  const showMap = mapUrl != null && !mapBroken && (placed.length > 0 || originPlaced);

  const goldLine = (
    <div className="flight-gold">
      Your Gold: <strong>{playerGold.toLocaleString()}</strong>
    </div>
  );

  if (!showMap) {
    return (
      <div className="flight-board">
        {goldLine}
        {destinations.length === 0 ? (
          <p className="flight-empty">
            You haven&apos;t discovered any other flight points yet. Explore the world to find more roosts.
          </p>
        ) : (
          <ul className="flight-list">
            {destinations.map((dest) => (
              <DestinationRow key={dest.roomId} dest={dest} onCommand={onCommand} />
            ))}
          </ul>
        )}
      </div>
    );
  }

  const skinVars: Record<string, string> = {};
  if (roostUrl) skinVars["--flight-roost"] = `url("${roostUrl}")`;

  return (
    <div className="flight-board flight-board-skinned" style={skinVars as CSSProperties}>
      {goldLine}

      <div className="flight-map">
        <img
          className="flight-map-img"
          src={mapUrl ?? undefined}
          alt="Map of Ambon"
          draggable={false}
          onError={() => setBrokenMapUrl(mapUrl)}
        />
        {originPlaced && (
          <div
            className="flight-marker flight-marker-origin"
            style={{ left: `${originMapX}%`, top: `${originMapY}%` }}
          >
            <span className="flight-marker-dot" />
            <span className="flight-marker-tip">
              <span className="flight-tip-name">You are here</span>
              {originName && <span className="flight-tip-meta">{originName}</span>}
            </span>
          </div>
        )}

        {placed.map((dest) => (
          <button
            key={dest.roomId}
            type="button"
            className={
              `flight-marker flight-marker-dest` +
              `${dest.affordable ? "" : " flight-marker-short"}` +
              `${roostUrl ? " flight-marker-art" : ""}`
            }
            style={{ left: `${dest.mapX}%`, top: `${dest.mapY}%` }}
            aria-disabled={!dest.affordable}
            aria-label={
              dest.affordable
                ? `Fly to ${dest.name} for ${dest.cost} gold`
                : `${dest.name} — not enough gold (${dest.cost})`
            }
            onClick={() => {
              if (dest.affordable) onCommand(`fly ${dest.roomId}`);
            }}
          >
            <span className="flight-marker-dot" />
            <span className="flight-marker-tip">
              <span className="flight-tip-name">{dest.name}</span>
              <span className="flight-tip-meta">
                {dest.zone} · {distanceLabel(dest.distance)}
              </span>
              <span className={`flight-tip-fare${dest.affordable ? "" : " flight-cost-short"}`}>
                {dest.cost.toLocaleString()} gold{dest.affordable ? "" : " — too dear"}
              </span>
            </span>
          </button>
        ))}
      </div>

      {unplaced.length > 0 && (
        <div className="flight-unplaced">
          <p className="flight-unplaced-head">Other roosts</p>
          <ul className="flight-list">
            {unplaced.map((dest) => (
              <DestinationRow key={dest.roomId} dest={dest} onCommand={onCommand} />
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
