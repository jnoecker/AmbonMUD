import { useState } from "react";
import type { QuestEntry, QuestAvailable, QuestNotification } from "../../types";

interface QuestPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  quests: QuestEntry[];
  questsAvailable: QuestAvailable[];
  questNotifications: QuestNotification[];
  onDismissQuestNotification: (id: string) => void;
  onAbandonQuest: (questName: string) => void;
  onAcceptQuest: (questName: string) => void;
}

export function QuestPanel({
  connected,
  hasCharacterProfile,
  quests,
  questsAvailable,
  questNotifications,
  onDismissQuestNotification,
  onAbandonQuest,
  onAcceptQuest,
}: QuestPanelProps) {
  const [expandedQuestId, setExpandedQuestId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<"active" | "available">("active");

  if (!connected) {
    return <p className="empty-note">Connect to view quests.</p>;
  }

  const hasAvailable = questsAvailable.length > 0;

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
                \u00D7
              </button>
            </div>
          ))}
        </div>
      )}

      {/* Tab bar */}
      {hasAvailable && (
        <div className="quest-tab-bar" role="tablist">
          <button
            type="button"
            role="tab"
            className={`quest-tab ${activeTab === "active" ? "quest-tab-active" : ""}`}
            aria-selected={activeTab === "active"}
            onClick={() => setActiveTab("active")}
          >
            Active
            {quests.length > 0 && <span className="quest-tab-badge">{quests.length}</span>}
          </button>
          <button
            type="button"
            role="tab"
            className={`quest-tab ${activeTab === "available" ? "quest-tab-active" : ""}`}
            aria-selected={activeTab === "available"}
            onClick={() => setActiveTab("available")}
          >
            Available
            <span className="quest-tab-badge quest-tab-badge-available">{questsAvailable.length}</span>
          </button>
        </div>
      )}

      {/* Active quests tab */}
      {(activeTab === "active" || !hasAvailable) && (
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
      {activeTab === "available" && hasAvailable && (
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
    </div>
  );
}
