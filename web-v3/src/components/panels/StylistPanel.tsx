import type { StylistRace, StylistState } from "../../types";

interface StylistPanelProps {
  stylistState: StylistState | null;
  onCommand: (cmd: string) => void;
}

function formatMod(key: string, value: number): string {
  return value >= 0 ? `${key} +${value}` : `${key} ${value}`;
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
      <div className="stylist-wallet">
        <span className="stylist-gold-icon" aria-hidden="true" />
        <span className="stylist-gold-amount">{stylistState.playerGold.toLocaleString()}</span>
        <span className="stylist-gold-label">gold</span>
        <span className="stylist-fee-tag">
          {stylistState.feeGold.toLocaleString()}g per change
        </span>
      </div>

      {stylistState.races.length === 0 ? (
        <p className="empty-note">No races available.</p>
      ) : (
        <div className="stylist-race-list">
          {stylistState.races.map((race: StylistRace) => {
            const isCurrent = race.id.toUpperCase() === stylistState.currentRace.toUpperCase();
            const mods = Object.entries(race.statMods).filter(([, v]) => v !== 0);
            return (
              <div
                key={race.id}
                className={`stylist-race-card${isCurrent ? " stylist-race-card-current" : ""}${!affordable && !isCurrent ? " stylist-race-card-unaffordable" : ""}`}
              >
                <div className="stylist-race-card-top">
                  <div className="stylist-race-card-info">
                    {race.image && (
                      <img
                        src={race.image}
                        alt=""
                        className="stylist-race-thumb"
                        loading="lazy"
                      />
                    )}
                    <div className="stylist-race-card-text">
                      <span className="stylist-race-name">
                        {race.displayName}
                        {isCurrent && <span className="stylist-badge-current">current</span>}
                      </span>
                      {race.description && (
                        <span className="stylist-race-desc">{race.description}</span>
                      )}
                      {mods.length > 0 && (
                        <span className="stylist-race-mods">
                          {mods.sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => (
                            <span key={k} className={`stylist-mod-chip${v >= 0 ? " stylist-mod-pos" : " stylist-mod-neg"}`}>
                              {formatMod(k, v)}
                            </span>
                          ))}
                        </span>
                      )}
                    </div>
                  </div>
                  <div className="stylist-race-card-action">
                    {!isCurrent && (
                      <span className={`stylist-race-price${affordable ? "" : " stylist-race-price-cant"}`}>
                        {stylistState.feeGold.toLocaleString()}g
                      </span>
                    )}
                    <button
                      type="button"
                      className="soft-button stylist-change-btn"
                      disabled={isCurrent || !affordable}
                      onClick={() => onCommand(`changerace ${race.id}`)}
                    >
                      {isCurrent ? "Current" : "Change"}
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
