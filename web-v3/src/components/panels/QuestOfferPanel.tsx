import type { QuestAvailable } from "../../types";

interface QuestOfferPanelProps {
  connected: boolean;
  questsAvailable: QuestAvailable[];
  onAcceptQuest: (questId: string) => void;
  onTurnInQuest: (questId: string) => void;
}

export function QuestOfferPanel({
  connected,
  questsAvailable,
  onAcceptQuest,
  onTurnInQuest,
}: QuestOfferPanelProps) {
  if (!connected) {
    return <p className="empty-note">Connect to view quests.</p>;
  }
  if (questsAvailable.length === 0) {
    return (
      <div className="quest-offer-empty">
        <span className="quest-empty-icon" aria-hidden="true">{"✦"}</span>
        <p className="empty-note">No quests on offer here.</p>
      </div>
    );
  }

  const ready = questsAvailable.filter((q) => q.readyToTurnIn);
  const offered = questsAvailable.filter((q) => !q.readyToTurnIn);

  return (
    <div className="quest-offer-panel">
      {ready.length > 0 && (
        <section className="quest-offer-section" aria-label="Ready to Turn In">
          <h3 className="quest-offer-section-title">Ready to Turn In</h3>
          <ul className="quest-available-list">
            {ready.map((quest) => (
              <QuestOfferCard
                key={quest.id}
                quest={quest}
                onAccept={onAcceptQuest}
                onTurnIn={onTurnInQuest}
              />
            ))}
          </ul>
        </section>
      )}
      {offered.length > 0 && (
        <section className="quest-offer-section" aria-label="Offered">
          {ready.length > 0 && <h3 className="quest-offer-section-title">Offered</h3>}
          <ul className="quest-available-list">
            {offered.map((quest) => (
              <QuestOfferCard
                key={quest.id}
                quest={quest}
                onAccept={onAcceptQuest}
                onTurnIn={onTurnInQuest}
              />
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

function QuestOfferCard({
  quest,
  onAccept,
  onTurnIn,
}: {
  quest: QuestAvailable;
  onAccept: (questId: string) => void;
  onTurnIn: (questId: string) => void;
}) {
  const locked = quest.lockedReason !== null;
  const currencyEntries = Object.entries(quest.rewards.currencies);
  const itemEntries = quest.rewards.items;
  const hasRewards =
    quest.rewards.xp > 0 ||
    quest.rewards.gold > 0 ||
    currencyEntries.length > 0 ||
    itemEntries.length > 0;

  return (
    <li className="quest-available-card">
      <div className="quest-available-header">
        <span className="quest-available-name">{quest.name}</span>
        {quest.levelRequired !== null && (
          <span className="quest-available-level-pill">Lv {quest.levelRequired}</span>
        )}
      </div>
      <p className="quest-available-description">{quest.description}</p>
      <ul className="quest-available-objectives">
        {quest.objectives.map((obj, idx) => (
          <li key={idx} className="quest-available-objective">
            <span className="quest-available-obj-icon" aria-hidden="true">{"○"}</span>
            <span>{obj.description}</span>
            <span className="quest-available-obj-count">
              {obj.current}/{obj.count}
            </span>
          </li>
        ))}
      </ul>
      {hasRewards && (
        <div className="quest-available-rewards">
          <span className="quest-available-rewards-label">Rewards:</span>
          {quest.rewards.xp > 0 && (
            <span className="quest-reward-xp">{quest.rewards.xp} XP</span>
          )}
          {quest.rewards.gold > 0 && (
            <span className="quest-reward-gold">{quest.rewards.gold} Gold</span>
          )}
          {currencyEntries.map(([id, amount]) => (
            <span key={id} className="quest-reward-gold">
              {amount} {id}
            </span>
          ))}
          {itemEntries.map((item) => (
            <span key={item.itemId} className="quest-reward-item">
              <span className="quest-reward-item-icon" aria-hidden="true">{"◆"}</span>
              {item.count > 1 ? `${item.count}× ` : ""}
              {item.displayName}
            </span>
          ))}
        </div>
      )}
      {quest.reputationRequired !== null && (
        <div className="quest-offer-requirements">
          {quest.reputationRequired !== null && (
            <span className="quest-offer-requirement">
              {quest.reputationRequired.factionName}
              {quest.reputationRequired.min !== null && ` ≥ ${quest.reputationRequired.min}`}
              {quest.reputationRequired.max !== null && ` ≤ ${quest.reputationRequired.max}`}
            </span>
          )}
        </div>
      )}
      {locked && (
        <p className="quest-offer-locked" id={`quest-offer-locked-${quest.id}`}>{quest.lockedReason}</p>
      )}
      {quest.readyToTurnIn ? (
        <button
          type="button"
          className="quest-accept-button quest-turnin-button"
          onClick={() => onTurnIn(quest.id)}
        >
          Turn In
        </button>
      ) : (
        <button
          type="button"
          className="quest-accept-button"
          onClick={() => onAccept(quest.id)}
          disabled={locked}
          aria-describedby={locked ? `quest-offer-locked-${quest.id}` : undefined}
        >
          Accept Quest
        </button>
      )}
    </li>
  );
}
