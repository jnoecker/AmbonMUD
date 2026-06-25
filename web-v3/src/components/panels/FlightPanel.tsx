import type { FlightDestination, FlightState } from "../../types";

interface FlightPanelProps {
  flightState: FlightState | null;
  onCommand: (cmd: string) => void;
}

/** Human-readable travel distance for a destination. */
function distanceLabel(dist: number | null): string {
  if (dist == null) return "a distant roost";
  if (dist === 1) return "1 room away";
  return `${dist} rooms away`;
}

/**
 * Flight master — pay gold to fast-travel to a flight point you've discovered.
 * Lists every reachable destination with its distance-scaled fare; clicking Fly
 * sends `fly <roomId>` (unambiguous), so the picked roost always matches the row.
 */
export function FlightPanel({ flightState, onCommand }: FlightPanelProps) {
  if (!flightState) {
    return (
      <div className="flight-board flight-board-empty">
        <p className="flight-empty">
          No griffins are roosting here. Visit a flight master and use the <code>flights</code> command.
        </p>
      </div>
    );
  }

  const { playerGold, destinations } = flightState;

  return (
    <div className="flight-board">
      <div className="flight-gold">
        Your Gold: <strong>{playerGold.toLocaleString()}</strong>
      </div>

      {destinations.length === 0 ? (
        <p className="flight-empty">
          You haven&apos;t discovered any other flight points yet. Explore the world to find more roosts.
        </p>
      ) : (
        <ul className="flight-list">
          {destinations.map((dest: FlightDestination) => (
            <li key={dest.roomId} className="flight-row">
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
          ))}
        </ul>
      )}
    </div>
  );
}
