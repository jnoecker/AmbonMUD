import { useEffect, useMemo, useState } from "react";
import { CURRENCY_METADATA, FACTION_METADATA, FACTION_TIERS } from "../../featureMetadata";
import type { CurrencyActivity, CurrencyBalance, DuelChallenge, DuelState, DungeonCatalogEntry, DungeonInfo, FactionActivity, FactionStanding, LotteryInfo, PetState, PrestigeInfo, RoomPlayer, SystemPanelView, UiFeedbackEntry, Vitals } from "../../types";

interface SystemsPanelProps {
  initialView: SystemPanelView;
  vitals: Vitals;
  nearbyPlayers: RoomPlayer[];
  currencies: CurrencyBalance[];
  currencyActivity: CurrencyActivity[];
  factions: FactionStanding[];
  factionActivity: FactionActivity[];
  petState: PetState | null;
  lotteryInfo: LotteryInfo | null;
  duelState: DuelState | null;
  duelChallenge: DuelChallenge | null;
  dungeonInfo: DungeonInfo | null;
  dungeonCatalog: DungeonCatalogEntry[];
  prestigeInfo: PrestigeInfo | null;
  uiFeedbackFeed: UiFeedbackEntry[];
  onCommand: (command: string) => void;
}

function formatCountdown(ms: number): string {
  if (ms <= 0) return "Drawing soon";
  const totalSeconds = Math.floor(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

function factionTrackPercent(reputation: number): number {
  return Math.min(100, Math.max(0, ((reputation + 1000) / 2000) * 100));
}

function factionTierSummary(tier: string): string {
  return FACTION_TIERS.find((entry) => entry.label === tier)?.short ?? "Current standing with this faction.";
}

function feedbackScopeForView(view: SystemPanelView): string {
  switch (view) {
    case "dungeon": return "dungeon";
    case "duel": return "duel";
    case "lottery": return "lottery";
    case "pet": return "pet";
    case "prestige": return "prestige";
    case "currencies": return "currencies";
    case "factions": return "factions";
    default: return "";
  }
}

function formatPrestigePerkType(type: string): string {
  switch (type) {
    case "STAT_BONUS": return "Stat Bonus";
    case "SKILL_POINT": return "Skill Point";
    case "MAX_HP": return "Max HP";
    case "MAX_MANA": return "Max Mana";
    case "TITLE": return "Title";
    default: return "";
  }
}

function formatRelativeTime(timestamp: number): string {
  const elapsedMs = Date.now() - timestamp;
  if (elapsedMs < 15_000) return "just now";
  const seconds = Math.floor(elapsedMs / 1000);
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ago`;
}

export function SystemsPanel({
  initialView,
  vitals,
  nearbyPlayers,
  currencies,
  currencyActivity,
  factions,
  factionActivity,
  petState,
  lotteryInfo,
  duelState,
  duelChallenge,
  dungeonInfo,
  dungeonCatalog,
  prestigeInfo,
  uiFeedbackFeed,
  onCommand,
}: SystemsPanelProps) {
  const [activeView, setActiveView] = useState<SystemPanelView>(initialView);
  const [ticketDraft, setTicketDraft] = useState("1");
  const [petNameDraft, setPetNameDraft] = useState("");
  const [selectedDifficulty, setSelectedDifficulty] = useState("normal");
  const [localMessage, setLocalMessage] = useState<string | null>(null);

  useEffect(() => {
    setActiveView(initialView);
  }, [initialView]);

  // nextDrawingMs is an absolute epoch timestamp; tick while the lottery
  // view is open so the pill counts down.
  const [now, setNow] = useState(() => Date.now());
  const lotteryViewOpen = activeView === "lottery" && lotteryInfo !== null;
  useEffect(() => {
    if (!lotteryViewOpen) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [lotteryViewOpen]);

  useEffect(() => {
    setPetNameDraft(petState?.active ? petState.name ?? "" : "");
  }, [petState?.active, petState?.name]);

  const sortedCurrencies = useMemo(
    () => [...currencies].sort((a, b) => b.balance - a.balance || a.name.localeCompare(b.name)),
    [currencies],
  );

  const sortedFactions = useMemo(
    () => [...factions].sort((a, b) => b.reputation - a.reputation || a.name.localeCompare(b.name)),
    [factions],
  );

  const sortedNearbyPlayers = useMemo(
    () => [...nearbyPlayers].sort((a, b) => a.name.localeCompare(b.name)),
    [nearbyPlayers],
  );

  const prestigeRequiredLevel = prestigeInfo?.requiredLevel ?? 0;
  const prestigePerksConfigured = (prestigeInfo?.perks.length ?? 0) > 0;
  const canPrestige = Boolean(
    prestigeInfo?.enabled &&
      prestigePerksConfigured &&
      prestigeRequiredLevel > 0 &&
      (vitals.level ?? 0) >= prestigeRequiredLevel &&
      prestigeInfo.currentRank < prestigeInfo.maxRank &&
      prestigeInfo.nextRankCost != null &&
      prestigeInfo.availableXp >= prestigeInfo.nextRankCost,
  );

  const openViews: Array<{ id: SystemPanelView; label: string }> = [
    { id: "dungeon", label: "Dungeon" },
    { id: "duel", label: "Dueling" },
    { id: "lottery", label: "Lottery" },
    { id: "pet", label: "Pet" },
    { id: "prestige", label: "Prestige" },
    { id: "currencies", label: "Wallet" },
    { id: "factions", label: "Factions" },
  ];

  const activeFeedback = useMemo(
    () =>
      [...uiFeedbackFeed]
        .reverse()
        .find((entry) => entry.scope === feedbackScopeForView(activeView)) ?? null,
    [activeView, uiFeedbackFeed],
  );

  const recentCurrencyActivity = useMemo(
    () => [...currencyActivity].slice(-5).reverse(),
    [currencyActivity],
  );

  const recentFactionActivity = useMemo(
    () => [...factionActivity].slice(-5).reverse(),
    [factionActivity],
  );

  const availableDungeons = useMemo(
    () => [...dungeonCatalog].sort((a, b) => a.minLevel - b.minLevel || a.name.localeCompare(b.name)),
    [dungeonCatalog],
  );

  return (
    <div className="systems-panel">
      <div className="panel-header">
        <span className="panel-title">Systems</span>
      </div>

      <div className="systems-tabs" role="tablist" aria-label="System views">
        {openViews.map((view) => (
          <button
            key={view.id}
            type="button"
            role="tab"
            aria-selected={activeView === view.id}
            className={`systems-tab ${activeView === view.id ? "systems-tab-active" : ""}`}
            onClick={() => setActiveView(view.id)}
          >
            {view.label}
          </button>
        ))}
      </div>

      {localMessage && <p className="systems-local-message">{localMessage}</p>}
      {activeFeedback && (
        <p className={`systems-local-message systems-local-message-${activeFeedback.type}`}>
          {activeFeedback.message}
        </p>
      )}

      <div className="systems-body">
        {activeView === "dungeon" && (
          <section className="systems-section">
            <h3 className="systems-title">Dungeon Runs</h3>

            {dungeonInfo?.active ? (
              <article className="systems-card systems-dungeon-active">
                <div className="systems-card-header">
                  <div>
                    <p className="systems-card-label">Active Run</p>
                    <h4>{dungeonInfo.name ?? "Dungeon in progress"}</h4>
                  </div>
                  {dungeonInfo.completed && <span className="systems-pill systems-pill-success">Completed</span>}
                </div>
                <dl className="systems-stat-grid">
                  <div><dt>Difficulty</dt><dd>{dungeonInfo.difficulty ?? "Unknown"}</dd></div>
                  <div><dt>Party</dt><dd>{dungeonInfo.memberCount ?? 1}</dd></div>
                  <div><dt>Rooms</dt><dd>{dungeonInfo.totalRooms ?? "-"}</dd></div>
                  <div><dt>Status</dt><dd>{dungeonInfo.completed ? "Boss defeated" : "In progress"}</dd></div>
                </dl>
                <div className="systems-action-row">
                  <button
                    type="button"
                    className="systems-primary-btn"
                    onClick={() => {
                      onCommand("dungeon enter resume");
                      setLocalMessage("Re-entering your active dungeon run.");
                    }}
                  >
                    Resume Run
                  </button>
                  <button
                    type="button"
                    className="systems-secondary-btn"
                    onClick={() => {
                      onCommand("dungeon leave");
                      setLocalMessage("Leaving the current dungeon.");
                    }}
                  >
                    Leave Dungeon
                  </button>
                </div>
              </article>
            ) : (
              <div className="systems-card-list">
                {availableDungeons.length === 0 && (
                  <article className="systems-card">
                    <p className="systems-card-copy">No dungeon catalog is available from the server right now.</p>
                  </article>
                )}
                {availableDungeons.map((dungeon) => {
                  const availableDifficulties = dungeon.difficulties.length > 0
                    ? dungeon.difficulties
                    : [{ id: "normal", label: "Normal", summary: "Standard dungeon pacing and rewards." }];
                  const chosenDifficulty = availableDifficulties.some((difficulty) => difficulty.id === selectedDifficulty)
                    ? selectedDifficulty
                    : availableDifficulties[0].id;
                  return (
                    <article key={dungeon.id} className="systems-card">
                      <div className="systems-card-header">
                        <div>
                          <p className="systems-card-label">Available Dungeon</p>
                          <h4>{dungeon.name}</h4>
                        </div>
                        <span className="systems-pill">Min Lv {dungeon.minLevel}</span>
                      </div>
                      <p className="systems-card-copy">{dungeon.description}</p>
                      {dungeon.portalHint && <p className="systems-detail-line">{dungeon.portalHint}</p>}
                      <div className="systems-choice-list">
                        {availableDifficulties.map((difficulty) => (
                          <button
                            key={difficulty.id}
                            type="button"
                            className={`systems-choice-card ${chosenDifficulty === difficulty.id ? "systems-choice-card-active" : ""}`}
                            onClick={() => setSelectedDifficulty(difficulty.id)}
                          >
                            <span className="systems-choice-title">{difficulty.label}</span>
                            <span className="systems-choice-copy">{difficulty.summary}</span>
                          </button>
                        ))}
                      </div>
                      <div className="systems-action-row">
                        <button
                          type="button"
                          className="systems-primary-btn"
                          onClick={() => {
                            onCommand(`dungeon enter ${dungeon.id} ${chosenDifficulty}`);
                            setLocalMessage(`Entering ${dungeon.name} on ${chosenDifficulty}.`);
                          }}
                        >
                          Enter Dungeon
                        </button>
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
          </section>
        )}

        {activeView === "duel" && (
          <section className="systems-section">
            <h3 className="systems-title">Dueling</h3>

            {duelState?.active ? (
              <article className="systems-card">
                <div className="systems-card-header">
                  <div>
                    <p className="systems-card-label">Active Duel</p>
                    <h4>{duelState.opponentName ?? "Unknown opponent"}</h4>
                  </div>
                  <span className="systems-pill systems-pill-danger">Live</span>
                </div>
                <div className="systems-action-row">
                  <button
                    type="button"
                    className="systems-secondary-btn"
                    onClick={() => {
                      onCommand("flee");
                      setLocalMessage("Attempting to flee the duel.");
                    }}
                  >
                    Flee Duel
                  </button>
                </div>
              </article>
            ) : duelChallenge ? (
              <article className="systems-card">
                <div className="systems-card-header">
                  <div>
                    <p className="systems-card-label">Challenge Status</p>
                    <h4>{duelChallenge.direction === "incoming" ? `${duelChallenge.challengerName} challenged you` : `Challenge sent to ${duelChallenge.targetName}`}</h4>
                  </div>
                  <span className="systems-pill">{duelChallenge.direction === "incoming" ? "Incoming" : "Outgoing"}</span>
                </div>
                {duelChallenge.direction === "incoming" && (
                  <div className="systems-action-row">
                    <button
                      type="button"
                      className="systems-primary-btn"
                      onClick={() => {
                        onCommand("duel accept");
                        setLocalMessage(`Accepting ${duelChallenge.challengerName}'s duel.`);
                      }}
                    >
                      Accept
                    </button>
                    <button
                      type="button"
                      className="systems-secondary-btn"
                      onClick={() => {
                        onCommand("duel decline");
                        setLocalMessage(`Declining ${duelChallenge.challengerName}'s duel.`);
                      }}
                    >
                      Decline
                    </button>
                  </div>
                )}
              </article>
            ) : (
              <article className="systems-card">
                <div className="systems-card-header">
                  <div>
                    <p className="systems-card-label">Nearby Duel Targets</p>
                    <h4>Start a new challenge</h4>
                  </div>
                </div>
                {sortedNearbyPlayers.length === 0 ? (
                  <p className="systems-card-copy">No nearby players are available to duel right now.</p>
                ) : (
                  <div className="systems-choice-list">
                    {sortedNearbyPlayers.map((player) => (
                      <button
                        key={player.name}
                        type="button"
                        className="systems-choice-card"
                        onClick={() => {
                          onCommand(`duel ${player.name}`);
                          setLocalMessage(`Challenging ${player.name} to a duel.`);
                        }}
                      >
                        <span className="systems-choice-title">{player.name}</span>
                        <span className="systems-choice-copy">Level {player.level}</span>
                      </button>
                    ))}
                  </div>
                )}
              </article>
            )}
          </section>
        )}

        {activeView === "lottery" && (
          <section className="systems-section">
            <h3 className="systems-title">Lottery</h3>

            {lotteryInfo ? (
              <article className="systems-card">
                <div className="systems-card-header">
                  <div>
                    <p className="systems-card-label">Current Drawing</p>
                    <h4>{lotteryInfo.jackpot.toLocaleString()} gold jackpot</h4>
                  </div>
                  <span className="systems-pill">{formatCountdown(lotteryInfo.nextDrawingMs - now)}</span>
                </div>
                <dl className="systems-stat-grid">
                  <div><dt>Your Tickets</dt><dd>{lotteryInfo.playerTickets}</dd></div>
                  <div><dt>Total Tickets</dt><dd>{lotteryInfo.totalTickets}</dd></div>
                  <div><dt>Ticket Cost</dt><dd>{lotteryInfo.ticketCost} gold</dd></div>
                  <div><dt>Per-Player Limit</dt><dd>{lotteryInfo.maxTicketsPerPlayer}</dd></div>
                </dl>
                <div className="systems-choice-list systems-choice-list-compact">
                  {[1, 3, 5].map((count) => (
                    <button
                      key={count}
                      type="button"
                      className="systems-choice-card"
                      onClick={() => {
                        onCommand(`lottery buy ${count}`);
                        setLocalMessage(`Buying ${count} ticket${count === 1 ? "" : "s"}.`);
                      }}
                    >
                      <span className="systems-choice-title">{count} ticket{count === 1 ? "" : "s"}</span>
                      <span className="systems-choice-copy">{(count * lotteryInfo.ticketCost).toLocaleString()} gold</span>
                    </button>
                  ))}
                </div>
                <div className="systems-action-row">
                  <input
                    type="number"
                    min={1}
                    max={lotteryInfo.maxTicketsPerPlayer}
                    step={1}
                    inputMode="numeric"
                    className="systems-number-input"
                    value={ticketDraft}
                    onChange={(event) => setTicketDraft(event.target.value)}
                  />
                  <button
                    type="button"
                    className="systems-primary-btn"
                    onClick={() => {
                      const count = Number(ticketDraft);
                      if (!Number.isSafeInteger(count) || count < 1) {
                        setLocalMessage("Enter a valid ticket quantity.");
                        return;
                      }
                      onCommand(`lottery buy ${count}`);
                      setLocalMessage(`Buying ${count} ticket${count === 1 ? "" : "s"}.`);
                    }}
                  >
                    Buy Custom Quantity
                  </button>
                  <button
                    type="button"
                    className="systems-secondary-btn"
                    onClick={() => {
                      onCommand("lottery");
                      setLocalMessage("Refreshing lottery information.");
                    }}
                  >
                    Refresh Info
                  </button>
                </div>
              </article>
            ) : (
              <p className="empty-note">Lottery information is not available right now.</p>
            )}
          </section>
        )}

        {activeView === "pet" && (
          <section className="systems-section">
            <h3 className="systems-title">Pet</h3>

            {petState?.active ? (
              <article className="systems-card">
                <div className="systems-card-header">
                  <div>
                    <p className="systems-card-label">Active Pet</p>
                    <h4>{petState.name ?? "Unnamed companion"}</h4>
                  </div>
                  <span className="systems-pill systems-pill-success">Summoned</span>
                </div>
                <dl className="systems-stat-grid">
                  <div><dt>Health</dt><dd>{petState.hp ?? 0} / {petState.maxHp ?? 0}</dd></div>
                  <div><dt>Damage</dt><dd>{petState.minDamage ?? 0}-{petState.maxDamage ?? 0}</dd></div>
                  <div><dt>Armor</dt><dd>{petState.armor ?? 0}</dd></div>
                </dl>
                <div className="systems-action-row systems-action-row-wrap">
                  <input
                    type="text"
                    className="systems-text-input"
                    placeholder="Rename your pet"
                    value={petNameDraft}
                    onChange={(event) => setPetNameDraft(event.target.value)}
                  />
                  <button
                    type="button"
                    className="systems-primary-btn"
                    onClick={() => {
                      const nextName = petNameDraft.trim();
                      if (nextName.length === 0) {
                        setLocalMessage("Enter a name before renaming your pet.");
                        return;
                      }
                      onCommand(`pet name ${nextName}`);
                      setLocalMessage(`Renaming your pet to ${nextName}.`);
                    }}
                  >
                    Rename Pet
                  </button>
                  <button
                    type="button"
                    className="systems-secondary-btn"
                    onClick={() => {
                      onCommand("pet dismiss");
                      setLocalMessage("Dismissing your active pet.");
                    }}
                  >
                    Dismiss Pet
                  </button>
                </div>
              </article>
            ) : (
              <article className="systems-card">
                <div className="systems-card-header">
                  <div>
                    <p className="systems-card-label">No Active Pet</p>
                    <h4>Your companion slot is empty</h4>
                  </div>
                </div>
                <p className="systems-card-copy">Use a pet ability to summon a companion.</p>
              </article>
            )}
          </section>
        )}

        {activeView === "prestige" && (() => {
          const requirementLabel = prestigeRequiredLevel > 0 ? `Level ${prestigeRequiredLevel}` : "Max level";
          const earnedPerks = prestigeInfo?.perks.filter((perk) => perk.earned) ?? [];
          return (
            <section className="systems-section">
              <h3 className="systems-title">Prestige</h3>

              {!prestigeInfo ? (
                <p className="empty-note">Prestige data is not available right now.</p>
              ) : !prestigeInfo.enabled ? (
                <article className="systems-card">
                  <div className="systems-card-header">
                    <div>
                      <p className="systems-card-label">Unavailable</p>
                      <h4>Prestige is disabled</h4>
                    </div>
                  </div>
                  <p className="systems-card-copy">Prestige is currently disabled on this server.</p>
                </article>
              ) : !prestigePerksConfigured ? (
                <article className="systems-card">
                  <div className="systems-card-header">
                    <div>
                      <p className="systems-card-label">Coming Soon</p>
                      <h4>Prestige perks are still being designed</h4>
                    </div>
                    <span className="systems-pill">Preview</span>
                  </div>
                  <p className="systems-card-copy">
                    Prestige is the endgame system that lets max-level characters trade surplus
                    experience for permanent perks &mdash; stat boosts, extra skill points, increased
                    HP and mana, and unique titles. The framework is live, but the per-rank perks
                    for this realm haven&rsquo;t been published yet.
                  </p>
                  <dl className="systems-stat-grid">
                    <div><dt>Requirement</dt><dd>{requirementLabel}</dd></div>
                    <div><dt>Current Level</dt><dd>{vitals.level ?? "-"}</dd></div>
                    <div><dt>Planned Max Rank</dt><dd>{prestigeInfo.maxRank > 0 ? prestigeInfo.maxRank : "—"}</dd></div>
                    <div><dt>Status</dt><dd>Awaiting perk data</dd></div>
                  </dl>
                  <p className="systems-card-copy">
                    Surplus XP earned past the level cap is preserved on your character and will
                    convert into prestige once perks go live.
                  </p>
                </article>
              ) : (
                <article className="systems-card">
                  <div className="systems-card-header">
                    <div>
                      <p className="systems-card-label">Current Rank</p>
                      <h4>Prestige {prestigeInfo.currentRank}</h4>
                    </div>
                    <span className="systems-pill">
                      {prestigeInfo.currentRank >= prestigeInfo.maxRank ? "Max rank" : `Cap ${prestigeInfo.maxRank}`}
                    </span>
                  </div>
                  <p className="systems-card-copy">
                    Spend surplus experience earned past the level cap to take a prestige rank.
                    Each rank grants a permanent perk and is preserved across deaths and respecs.
                  </p>
                  <dl className="systems-stat-grid">
                    <div><dt>Available XP</dt><dd>{prestigeInfo.availableXp.toLocaleString()}</dd></div>
                    <div><dt>Next Rank Cost</dt><dd>{prestigeInfo.nextRankCost?.toLocaleString() ?? "Maxed"}</dd></div>
                    <div><dt>Current Level</dt><dd>{vitals.level ?? "-"}</dd></div>
                    <div><dt>Requirement</dt><dd>{requirementLabel}</dd></div>
                  </dl>
                  <div className="systems-callout">
                    {prestigeInfo.currentRank >= prestigeInfo.maxRank
                      ? "You have reached the maximum prestige rank."
                      : prestigeRequiredLevel > 0 && (vitals.level ?? 0) < prestigeRequiredLevel
                        ? `Reach level ${prestigeRequiredLevel} to prestige.`
                        : prestigeInfo.nextRankCost != null && prestigeInfo.availableXp < prestigeInfo.nextRankCost
                          ? "You need more surplus XP before you can advance."
                          : "You are eligible to take the next prestige rank."}
                  </div>
                  <div className="systems-action-row">
                    <button
                      type="button"
                      className="systems-primary-btn"
                      disabled={!canPrestige}
                      onClick={() => {
                        onCommand("prestige");
                        setLocalMessage("Attempting to advance your prestige rank.");
                      }}
                    >
                      Prestige Up
                    </button>
                  </div>
                  {earnedPerks.length > 0 && (
                    <div className="systems-perk-summary">
                      <p className="systems-card-label">Bonuses Earned</p>
                      <ul className="systems-bullet-list">
                        {earnedPerks.map((perk) => (
                          <li key={`earned-${perk.rank}`}>
                            <strong>Rank {perk.rank}:</strong> {perk.description}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                  <div className="systems-perk-list">
                    {prestigeInfo.perks.map((perk) => {
                      const typeLabel = formatPrestigePerkType(perk.type);
                      const rankLabel = perk.earned
                        ? `Rank ${perk.rank} · Earned`
                        : typeLabel
                          ? `Rank ${perk.rank} · ${typeLabel}`
                          : `Rank ${perk.rank}`;
                      return (
                        <div key={perk.rank} className={`systems-perk-card ${perk.earned ? "systems-perk-card-earned" : ""}`}>
                          <span className="systems-perk-rank">{rankLabel}</span>
                          <span className="systems-perk-desc">{perk.description}</span>
                        </div>
                      );
                    })}
                  </div>
                </article>
              )}
            </section>
          );
        })()}

        {activeView === "currencies" && (
          <section className="systems-section">
            <h3 className="systems-title">Wallet</h3>

            {sortedCurrencies.length === 0 ? (
              <article className="systems-card">
                <p className="systems-card-copy">No alternate currencies are active for this character yet.</p>
              </article>
            ) : (
              <div className="systems-card-list">
                {sortedCurrencies.map((currency) => {
                  const meta = CURRENCY_METADATA[currency.id];
                  return (
                    <article key={currency.id} className="systems-card">
                      <div className="systems-card-header">
                        <div>
                          <p className="systems-card-label">{currency.abbreviation}</p>
                          <h4>{currency.name}</h4>
                        </div>
                        <span className="systems-pill">{currency.balance.toLocaleString()}</span>
                      </div>
                      <p className="systems-card-copy">{meta?.description ?? "Server-managed alternate currency."}</p>
                      {meta?.gainHints && (
                        <ul className="systems-bullet-list">
                          {meta.gainHints.map((hint) => (
                            <li key={hint}>{hint}</li>
                          ))}
                        </ul>
                      )}
                    </article>
                  );
                })}
              </div>
            )}

            <article className="systems-card">
              <div className="systems-card-header">
                <div>
                  <p className="systems-card-label">Recent Activity</p>
                  <h4>Balance changes</h4>
                </div>
              </div>
              {recentCurrencyActivity.length === 0 ? (
                <p className="systems-card-copy">No recent activity.</p>
              ) : (
                <div className="systems-feed">
                  {recentCurrencyActivity.map((entry) => (
                    <div
                      key={entry.id}
                      className={`systems-feed-item ${entry.delta >= 0 ? "systems-feed-item-positive" : "systems-feed-item-negative"}`}
                    >
                      <div>
                        <strong>
                          {entry.delta >= 0 ? "+" : ""}
                          {entry.delta.toLocaleString()} {entry.abbreviation || entry.name}
                        </strong>
                        <p className="systems-feed-copy">
                          New balance: {entry.balance.toLocaleString()} {entry.abbreviation || entry.name}
                        </p>
                      </div>
                      <span className="systems-feed-time">{formatRelativeTime(entry.receivedAt)}</span>
                    </div>
                  ))}
                </div>
              )}
            </article>
          </section>
        )}

        {activeView === "factions" && (
          <section className="systems-section">
            <h3 className="systems-title">Factions</h3>

            {sortedFactions.length === 0 ? (
              <article className="systems-card">
                <p className="systems-card-copy">No faction standing data has been recorded yet.</p>
              </article>
            ) : (
              <div className="systems-card-list">
                {sortedFactions.map((faction) => {
                  const meta = FACTION_METADATA[faction.id];
                  return (
                    <article key={faction.id} className="systems-card">
                      <div className="systems-card-header">
                        <div>
                          <p className="systems-card-label">Current Tier</p>
                          <h4>{faction.name}</h4>
                        </div>
                        <span className="systems-pill">{faction.tier}</span>
                      </div>
                      <div className="systems-meter">
                        <div className="systems-meter-track">
                          <span className="systems-meter-fill" style={{ width: `${factionTrackPercent(faction.reputation)}%` }} />
                        </div>
                        <span className="systems-meter-value">{faction.reputation}</span>
                      </div>
                      <p className="systems-card-copy">{meta?.description ?? "This faction influences quests and combat reputation changes."}</p>
                      <p className="systems-detail-line">{factionTierSummary(faction.tier)}</p>
                      {meta?.gainHints && (
                        <ul className="systems-bullet-list">
                          {meta.gainHints.map((hint) => (
                            <li key={hint}>{hint}</li>
                          ))}
                        </ul>
                      )}
                    </article>
                  );
                })}
              </div>
            )}

            <article className="systems-card">
              <div className="systems-card-header">
                <div>
                  <p className="systems-card-label">Recent Activity</p>
                  <h4>Reputation changes</h4>
                </div>
              </div>
              {recentFactionActivity.length === 0 ? (
                <p className="systems-card-copy">No recent activity.</p>
              ) : (
                <div className="systems-feed">
                  {recentFactionActivity.map((entry) => (
                    <div
                      key={entry.id}
                      className={`systems-feed-item ${entry.delta >= 0 ? "systems-feed-item-positive" : "systems-feed-item-negative"}`}
                    >
                      <div>
                        <strong>
                          {entry.name}: {entry.delta >= 0 ? "+" : ""}
                          {entry.delta}
                        </strong>
                        <p className="systems-feed-copy">
                          {entry.tier} at {entry.reputation}
                        </p>
                      </div>
                      <span className="systems-feed-time">{formatRelativeTime(entry.receivedAt)}</span>
                    </div>
                  ))}
                </div>
              )}
            </article>
          </section>
        )}
      </div>
    </div>
  );
}
