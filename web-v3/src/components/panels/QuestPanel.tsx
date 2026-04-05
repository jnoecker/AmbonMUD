import { useEffect, useState } from "react";
import type {
  QuestEntry,
  QuestAvailable,
  QuestNotification,
  DailyQuestBoard,
  DailyQuestEntry,
  AutoQuest,
  GlobalQuest,
} from "../../types";

type QuestTab = "active" | "available" | "daily" | "weekly" | "bounty" | "global";

interface QuestPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  quests: QuestEntry[];
  questsAvailable: QuestAvailable[];
  questNotifications: QuestNotification[];
  dailyQuests: DailyQuestBoard | null;
  weeklyQuests: DailyQuestEntry[];
  autoQuest: AutoQuest | null;
  globalQuest: GlobalQuest | null;
  onDismissQuestNotification: (id: string) => void;
  onAbandonQuest: (questName: string) => void;
  onAcceptQuest: (questName: string) => void;
  onCommand: (cmd: string) => void;
}

function formatTimeRemaining(ms: number): string {
  if (ms <= 0) return "Expired";
  const totalSeconds = Math.floor(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

function questTypeLabel(type: string): string {
  switch (type) {
    case "kill": return "Slay";
    case "gather": return "Gather";
    case "craft": return "Craft";
    case "dungeon": return "Dungeon";
    case "pvpKill": return "PvP";
    default: return type;
  }
}

export function QuestPanel({
  connected,
  hasCharacterProfile,
  quests,
  questsAvailable,
  questNotifications,
  dailyQuests,
  weeklyQuests,
  autoQuest,
  globalQuest,
  onDismissQuestNotification,
  onAbandonQuest,
  onAcceptQuest,
  onCommand,
}: QuestPanelProps) {
  const [expandedQuestId, setExpandedQuestId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<QuestTab>("active");
  // Ticking clock for the global quest timer — refreshes every 10s while a global quest is active
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!globalQuest?.active || !globalQuest.endsAtMs) return;
    const interval = setInterval(() => setNow(Date.now()), 10_000);
    return () => clearInterval(interval);
  }, [globalQuest?.active, globalQuest?.endsAtMs]);

  if (!connected) {
    return <p className="empty-note">Connect to view quests.</p>;
  }

  const tabs: Array<{ id: QuestTab; label: string; badge?: number }> = [
    { id: "active", label: "Active", badge: quests.length > 0 ? quests.length : undefined },
    ...(questsAvailable.length > 0 ? [{ id: "available" as QuestTab, label: "Available", badge: questsAvailable.length }] : []),
    { id: "daily", label: "Daily", badge: dailyQuests ? dailyQuests.quests.filter((q) => !q.completed).length : undefined },
    { id: "weekly", label: "Weekly", badge: weeklyQuests.filter((q) => !q.completed).length > 0 ? weeklyQuests.filter((q) => !q.completed).length : undefined },
    { id: "bounty", label: "Bounty" },
    { id: "global", label: "Global" },
  ];

  return (
    <div className="quest-panel">
      {/* Notifications */}
      {questNotifications.length > 0 && (
        <div className="quest-notifications">
          {questNotifications.map((n) => (
            <div
              key={n.id}
              className={`quest-notification quest-notification-${n.event}`}
              role="status"
            >
              <span className="quest-notification-icon">
                {n.event === "complete" ? "\u2726" : "\u25B2"}
              </span>
              <span className="quest-notification-text">
                {n.event === "complete" ? "Completed: " : "Updated: "}
                <strong>{n.questName}</strong>
              </span>
              <button
                type="button"
                className="quest-notification-dismiss"
                onClick={() => onDismissQuestNotification(n.id)}
                aria-label="Dismiss notification"
              >
                {"\u00D7"}
              </button>
            </div>
          ))}
        </div>
      )}

      {/* Tab bar */}
      <div className="quest-tab-bar" role="tablist">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            className={`quest-tab ${activeTab === tab.id ? "quest-tab-active" : ""}`}
            aria-selected={activeTab === tab.id}
            onClick={() => setActiveTab(tab.id)}
          >
            {tab.label}
            {tab.badge != null && tab.badge > 0 && (
              <span className={`quest-tab-badge ${tab.id === "available" ? "quest-tab-badge-available" : ""}`}>
                {tab.badge}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* Active quests tab */}
      {activeTab === "active" && (
        <section
          className="quest-panel-section"
          role="tabpanel"
          aria-label="Active Quests"
        >
          {quests.length === 0 ? (
            <div className="quest-empty-state">
              <span className="quest-empty-icon">{"\u2726"}</span>
              <p className="empty-note">
                {hasCharacterProfile
                  ? "No active quests. Talk to NPCs to discover available quests."
                  : "Quests will appear here during gameplay."}
              </p>
            </div>
          ) : (
            <ul className="quest-list">
              {quests.map((quest) => {
                const isExpanded = expandedQuestId === quest.id;
                const totalObjectives = quest.objectives.length;
                const completedObjectives = quest.objectives.filter(
                  (o) => o.current >= o.required,
                ).length;
                const allDone = completedObjectives === totalObjectives;
                const overallProgress = totalObjectives > 0
                  ? quest.objectives.reduce((sum, o) => sum + Math.min(o.current, o.required), 0) /
                    quest.objectives.reduce((sum, o) => sum + o.required, 0)
                  : 0;
                return (
                  <li
                    key={quest.id}
                    className={`quest-item ${isExpanded ? "quest-item-expanded" : ""} ${allDone ? "quest-item-complete" : ""}`}
                  >
                    <button
                      type="button"
                      className="quest-item-header"
                      onClick={() => setExpandedQuestId(isExpanded ? null : quest.id)}
                      aria-expanded={isExpanded}
                    >
                      <span className="quest-item-icon">{allDone ? "\u2713" : "\u2726"}</span>
                      <span className="quest-item-name">{quest.name}</span>
                      <span className="quest-item-progress-badge">
                        {completedObjectives}/{totalObjectives}
                      </span>
                    </button>

                    {/* Mini progress bar visible when collapsed */}
                    {!isExpanded && (
                      <div className="quest-item-mini-progress">
                        <div className="meter-track quest-mini-track">
                          <span
                            className={`meter-fill ${allDone ? "meter-fill-quest-done" : "meter-fill-quest"}`}
                            style={{ width: `${Math.min(100, overallProgress * 100)}%` }}
                          />
                        </div>
                      </div>
                    )}

                    {isExpanded && (
                      <div className="quest-item-details">
                        <p className="quest-item-description">{quest.description}</p>
                        <ul className="quest-objectives">
                          {quest.objectives.map((obj, idx) => {
                            const done = obj.current >= obj.required;
                            const progress = obj.required > 0
                              ? Math.min(100, (obj.current / obj.required) * 100)
                              : 0;
                            return (
                              <li
                                key={idx}
                                className={`quest-objective ${done ? "quest-objective-done" : ""}`}
                              >
                                <div className="quest-objective-header">
                                  <span className="quest-objective-check">
                                    {done ? "\u2713" : "\u25CB"}
                                  </span>
                                  <span className="quest-objective-text">{obj.description}</span>
                                  <span className="quest-objective-count">
                                    {obj.current}/{obj.required}
                                  </span>
                                </div>
                                {!done && (
                                  <div className="meter-track quest-objective-track">
                                    <span
                                      className="meter-fill meter-fill-quest"
                                      style={{ width: `${progress}%` }}
                                    />
                                  </div>
                                )}
                              </li>
                            );
                          })}
                        </ul>
                        <div className="quest-item-actions">
                          <button
                            type="button"
                            className="quest-abandon-button"
                            onClick={() => onAbandonQuest(quest.name)}
                          >
                            Abandon
                          </button>
                        </div>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      )}

      {/* Available quests tab */}
      {activeTab === "available" && questsAvailable.length > 0 && (
        <section
          className="quest-panel-section"
          role="tabpanel"
          aria-label="Available Quests"
        >
          <ul className="quest-available-list">
            {questsAvailable.map((quest) => (
              <li key={quest.id} className="quest-available-card">
                <div className="quest-available-header">
                  <span className="quest-available-icon">{"\u2726"}</span>
                  <span className="quest-available-name">{quest.name}</span>
                </div>
                <p className="quest-available-description">{quest.description}</p>
                <ul className="quest-available-objectives">
                  {quest.objectives.map((obj, idx) => (
                    <li key={idx} className="quest-available-objective">
                      <span className="quest-available-obj-icon">{"\u25CB"}</span>
                      <span>{obj.description}</span>
                      <span className="quest-available-obj-count">0/{obj.count}</span>
                    </li>
                  ))}
                </ul>
                {(quest.rewards.xp > 0 || quest.rewards.gold > 0) && (
                  <div className="quest-available-rewards">
                    <span className="quest-available-rewards-label">Rewards:</span>
                    {quest.rewards.xp > 0 && (
                      <span className="quest-reward-xp">{quest.rewards.xp} XP</span>
                    )}
                    {quest.rewards.gold > 0 && (
                      <span className="quest-reward-gold">{quest.rewards.gold} Gold</span>
                    )}
                  </div>
                )}
                <button
                  type="button"
                  className="quest-accept-button"
                  onClick={() => onAcceptQuest(quest.name)}
                >
                  Accept Quest
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* Daily quests tab */}
      {activeTab === "daily" && (
        <section
          className="quest-panel-section"
          role="tabpanel"
          aria-label="Daily Quests"
        >
          {dailyQuests && dailyQuests.quests.length > 0 ? (
            <div className="quest-timed-board">
              {dailyQuests.streakDays > 0 && (
                <div className="quest-streak-banner">
                  <span className="quest-streak-icon">{"\u2605"}</span>
                  <span className="quest-streak-text">{dailyQuests.streakDays}-day streak!</span>
                </div>
              )}
              <ul className="quest-timed-list">
                {dailyQuests.quests.map((q) => (
                  <DailyQuestRow key={q.index} quest={q} />
                ))}
              </ul>
            </div>
          ) : (
            <div className="quest-empty-state">
              <span className="quest-empty-icon">{"\u2726"}</span>
              <p className="empty-note">No daily quests loaded.</p>
              <button
                type="button"
                className="quest-load-button"
                onClick={() => onCommand("daily")}
              >
                Load Daily Quests
              </button>
            </div>
          )}
        </section>
      )}

      {/* Weekly quests tab */}
      {activeTab === "weekly" && (
        <section
          className="quest-panel-section"
          role="tabpanel"
          aria-label="Weekly Quests"
        >
          {weeklyQuests.length > 0 ? (
            <ul className="quest-timed-list">
              {weeklyQuests.map((q) => (
                <DailyQuestRow key={q.index} quest={q} />
              ))}
            </ul>
          ) : (
            <div className="quest-empty-state">
              <span className="quest-empty-icon">{"\u2726"}</span>
              <p className="empty-note">No weekly quests loaded.</p>
              <button
                type="button"
                className="quest-load-button"
                onClick={() => onCommand("weekly")}
              >
                Load Weekly Quests
              </button>
            </div>
          )}
        </section>
      )}

      {/* Bounty (auto-quest) tab */}
      {activeTab === "bounty" && (
        <section
          className="quest-panel-section"
          role="tabpanel"
          aria-label="Bounty Quest"
        >
          {autoQuest && autoQuest.active ? (
            <div className="quest-bounty-card">
              <div className="quest-bounty-header">
                <span className="quest-bounty-icon">{"\u2694"}</span>
                <span className="quest-bounty-title">
                  Hunt: {autoQuest.targetMobName ?? "Unknown"}
                </span>
              </div>
              {autoQuest.killsRequired != null && autoQuest.killsRequired > 0 && (
                <div className="quest-bounty-progress">
                  <div className="quest-objective-header">
                    <span className="quest-objective-text">
                      Defeat {autoQuest.targetMobName}
                    </span>
                    <span className="quest-objective-count">
                      {autoQuest.killsCompleted ?? 0}/{autoQuest.killsRequired}
                    </span>
                  </div>
                  <div className="meter-track">
                    <span
                      className={`meter-fill ${(autoQuest.killsCompleted ?? 0) >= autoQuest.killsRequired ? "meter-fill-quest-done" : "meter-fill-quest"}`}
                      style={{ width: `${Math.min(100, ((autoQuest.killsCompleted ?? 0) / autoQuest.killsRequired) * 100)}%` }}
                    />
                  </div>
                </div>
              )}
              <div className="quest-bounty-details">
                {autoQuest.rewardXp != null && autoQuest.rewardXp > 0 && (
                  <span className="quest-reward-xp">{autoQuest.rewardXp} XP</span>
                )}
                {autoQuest.rewardGold != null && autoQuest.rewardGold > 0 && (
                  <span className="quest-reward-gold">{autoQuest.rewardGold} Gold</span>
                )}
                {autoQuest.timeRemainingMs != null && autoQuest.timeRemainingMs > 0 && (
                  <span className="quest-bounty-timer">
                    {formatTimeRemaining(autoQuest.timeRemainingMs)}
                  </span>
                )}
              </div>
            </div>
          ) : (
            <div className="quest-empty-state">
              <span className="quest-empty-icon">{"\u2694"}</span>
              <p className="empty-note">
                {autoQuest && !autoQuest.active
                  ? "No active bounty. Request one to get started."
                  : "Bounty quests are procedurally generated hunts."}
              </p>
              <button
                type="button"
                className="quest-load-button"
                onClick={() => onCommand("quest auto")}
              >
                Request Bounty
              </button>
            </div>
          )}
        </section>
      )}

      {/* Global quest tab */}
      {activeTab === "global" && (
        <section
          className="quest-panel-section"
          role="tabpanel"
          aria-label="Global Quest"
        >
          {globalQuest && globalQuest.active ? (
            <div className="quest-global-card">
              <div className="quest-global-header">
                <span className="quest-global-icon">{"\u2604"}</span>
                <span className="quest-global-title">
                  {globalQuest.objective ?? "Global Quest"}
                </span>
                {globalQuest.completed && (
                  <span className="quest-global-complete-badge">Complete</span>
                )}
              </div>
              {globalQuest.targetCount != null && globalQuest.targetCount > 0 && (
                <div className="quest-global-progress">
                  <div className="quest-objective-header">
                    <span className="quest-objective-text">Your progress</span>
                    <span className="quest-objective-count">
                      {globalQuest.playerProgress ?? 0}/{globalQuest.targetCount}
                    </span>
                  </div>
                  <div className="meter-track">
                    <span
                      className={`meter-fill ${globalQuest.completed ? "meter-fill-quest-done" : "meter-fill-quest"}`}
                      style={{ width: `${Math.min(100, ((globalQuest.playerProgress ?? 0) / globalQuest.targetCount) * 100)}%` }}
                    />
                  </div>
                </div>
              )}
              {globalQuest.endsAtMs != null && globalQuest.endsAtMs > 0 && (
                <div className="quest-global-timer">
                  Ends in: {formatTimeRemaining(globalQuest.endsAtMs - now)}
                </div>
              )}
              {globalQuest.leaderboard && globalQuest.leaderboard.length > 0 && (
                <div className="quest-global-leaderboard">
                  <div className="quest-global-leaderboard-title">Leaderboard</div>
                  <ul className="quest-global-leaderboard-list">
                    {globalQuest.leaderboard.map((entry) => (
                      <li key={entry.rank} className="quest-global-leaderboard-row">
                        <span className="quest-global-lb-rank">#{entry.rank}</span>
                        <span className="quest-global-lb-name">{entry.name}</span>
                        <span className="quest-global-lb-progress">{entry.progress}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          ) : (
            <div className="quest-empty-state">
              <span className="quest-empty-icon">{"\u2604"}</span>
              <p className="empty-note">No global quest active.</p>
              <button
                type="button"
                className="quest-load-button"
                onClick={() => onCommand("globalquest")}
              >
                Check Status
              </button>
            </div>
          )}
        </section>
      )}
    </div>
  );
}

/* Shared row component for daily/weekly quest entries */
function DailyQuestRow({ quest }: { quest: DailyQuestEntry }) {
  const progress = quest.required > 0
    ? Math.min(100, (quest.current / quest.required) * 100)
    : 0;

  return (
    <li className={`quest-timed-row ${quest.completed ? "quest-timed-row-done" : ""}`}>
      <div className="quest-timed-row-header">
        <span className="quest-timed-type-badge">{questTypeLabel(quest.type)}</span>
        <span className="quest-timed-description">{quest.description}</span>
        <span className="quest-objective-count">
          {quest.current}/{quest.required}
        </span>
      </div>
      {!quest.completed && (
        <div className="meter-track quest-timed-track">
          <span
            className="meter-fill meter-fill-quest"
            style={{ width: `${progress}%` }}
          />
        </div>
      )}
      {quest.completed && (
        <div className="quest-timed-done-check">{"\u2713"} Complete</div>
      )}
      <div className="quest-timed-rewards">
        {quest.xpReward > 0 && <span className="quest-reward-xp">{quest.xpReward} XP</span>}
        {quest.goldReward > 0 && <span className="quest-reward-gold">{quest.goldReward} Gold</span>}
      </div>
    </li>
  );
}
