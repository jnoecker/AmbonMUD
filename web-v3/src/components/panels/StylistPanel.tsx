import type { StylistRace, StylistState } from "../../types";

interface StylistPanelProps {
  stylistState: StylistState | null;
  onCommand: (cmd: string) => void;
}

function formatMods(statMods: Record<string, number>): string {
  const entries = Object.entries(statMods).filter(([, v]) => v !== 0);
  if (entries.length === 0) return "no stat change";
  return entries
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => (v >= 0 ? `${k} +${v}` : `${k} ${v}`))
    .join(", ");
}

export function StylistPanel({ stylistState, onCommand }: StylistPanelProps) {
  if (!stylistState) {
    return (
      <div className="stylist-panel">
        <div className="panel-header">
          <span className="panel-title">Stylist</span>
        </div>
        <div className="stylist-empty-state">
          <span className="stylist-empty-icon">{"\u{2728}"}</span>
          <p className="empty-note">The mirror is dim.</p>
          <p className="stylist-hint">
            Visit a stylist room and use the <code>stylist</code> command.
          </p>
        </div>
      </div>
    );
  }

  const affordable = stylistState.playerGold >= stylistState.feeGold;

  return (
    <div className="stylist-panel">
      <div className="panel-header">
        <span className="panel-title">Stylist</span>
      </div>

      <div className="stylist-content">
        <div className="stylist-summary">
          <div className="stylist-summary-row">
            <span className="stylist-summary-label">Current Race</span>
            <span className="stylist-summary-value">{stylistState.currentRace}</span>
          </div>
          <div className="stylist-summary-row">
            <span className="stylist-summary-label">Fee</span>
            <span
              className={`stylist-summary-value${affordable ? "" : " stylist-fee-short"}`}
            >
              {stylistState.feeGold.toLocaleString()} gold
            </span>
          </div>
          <div className="stylist-summary-row">
            <span className="stylist-summary-label">Your Gold</span>
            <span className="stylist-summary-value">
              {stylistState.playerGold.toLocaleString()}
            </span>
          </div>
        </div>

        {stylistState.races.length === 0 ? (
          <p className="stylist-empty">No races available.</p>
        ) : (
          <ul className="stylist-race-list">
            {stylistState.races.map((race: StylistRace) => {
              const isCurrent = race.id.toUpperCase() === stylistState.currentRace.toUpperCase();
              return (
                <li key={race.id} className="stylist-race-row">
                  <div className="stylist-race-info">
                    {race.image && (
                      <img
                        src={race.image}
                        alt=""
                        className="stylist-race-image"
                        loading="lazy"
                      />
                    )}
                    <div className="stylist-race-text">
                      <div className="stylist-race-name">
                        {race.displayName}
                        {isCurrent && <span className="stylist-race-current"> (current)</span>}
                      </div>
                      <div className="stylist-race-mods">{formatMods(race.statMods)}</div>
                      {race.description && (
                        <div className="stylist-race-desc">{race.description}</div>
                      )}
                    </div>
                  </div>
                  <button
                    type="button"
                    className="stylist-change-btn"
                    disabled={isCurrent || !affordable}
                    onClick={() => onCommand(`changerace ${race.id}`)}
                  >
                    {isCurrent ? "Current" : "Change"}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}
