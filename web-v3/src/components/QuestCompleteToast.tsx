import { useEffect, useMemo, useState } from "react";
import type { QuestNotification } from "../types";

interface QuestCompleteToastProps {
  notifications: QuestNotification[];
  onDismiss: (id: string) => void;
}

/**
 * Top-right toast surfaced when a Quest.Complete GMCP packet arrives.
 *
 * Picks up the most recent unhandled "complete"-type QuestNotification, shows
 * quest name + description + rewards for ~5s, then fades. Click anywhere on
 * the toast to dismiss early. Stays out of the way of gameplay.
 */
export function QuestCompleteToast({ notifications, onDismiss }: QuestCompleteToastProps) {
  const current = useMemo(
    () => notifications.find((n) => n.event === "complete" || n.event === "accept") ?? null,
    [notifications],
  );
  if (!current) return null;
  return (
    <QuestCompleteToastInner
      key={current.id}
      notification={current}
      onDismiss={() => onDismiss(current.id)}
    />
  );
}

interface InnerProps {
  notification: QuestNotification;
  onDismiss: () => void;
}

function QuestCompleteToastInner({ notification, onDismiss }: InnerProps) {
  const [closing, setClosing] = useState(false);

  useEffect(() => {
    const fade = window.setTimeout(() => setClosing(true), 5000);
    return () => window.clearTimeout(fade);
  }, []);

  useEffect(() => {
    if (!closing) return;
    const clear = window.setTimeout(() => onDismiss(), 350);
    return () => window.clearTimeout(clear);
  }, [closing, onDismiss]);

  const rewards = notification.rewards;
  const currencyEntries = rewards ? Object.entries(rewards.currencies) : [];
  const itemEntries = rewards?.items ?? [];
  const hasRewards =
    !!rewards &&
    (rewards.xp > 0 || rewards.gold > 0 || currencyEntries.length > 0 || itemEntries.length > 0);

  return (
    <div
      className={`quest-complete-toast${closing ? " quest-complete-toast-closing" : ""}`}
      role="status"
      aria-live="polite"
      onClick={() => setClosing(true)}
    >
      <div className="quest-complete-toast-header">
        <span className="quest-complete-toast-icon" aria-hidden="true">{notification.event === "accept" ? "✦" : "✨"}</span>
        <div className="quest-complete-toast-titles">
          <span className="quest-complete-toast-eyebrow">{notification.event === "accept" ? "Quest Accepted" : "Quest Complete"}</span>
          <span className="quest-complete-toast-name">{notification.questName}</span>
        </div>
      </div>
      {notification.questDescription && (
        <p className="quest-complete-toast-desc">{notification.questDescription}</p>
      )}
      {hasRewards && (
        <ul className="quest-complete-toast-rewards" role="list">
          {rewards!.xp > 0 && (
            <li className="quest-complete-toast-reward quest-complete-toast-reward-xp">
              <span className="quest-complete-toast-reward-amount">+{rewards!.xp}</span>
              <span className="quest-complete-toast-reward-label">XP</span>
            </li>
          )}
          {rewards!.gold > 0 && (
            <li className="quest-complete-toast-reward quest-complete-toast-reward-gold">
              <span className="quest-complete-toast-reward-amount">+{rewards!.gold}</span>
              <span className="quest-complete-toast-reward-label">Gold</span>
            </li>
          )}
          {currencyEntries.map(([id, amount]) => (
            <li key={id} className="quest-complete-toast-reward quest-complete-toast-reward-currency">
              <span className="quest-complete-toast-reward-amount">+{amount}</span>
              <span className="quest-complete-toast-reward-label">{id}</span>
            </li>
          ))}
          {itemEntries.map((item) => (
            <li
              key={item.itemId}
              className="quest-complete-toast-reward quest-complete-toast-reward-item"
            >
              <span className="quest-complete-toast-reward-icon" aria-hidden="true">{"◆"}</span>
              <span className="quest-complete-toast-reward-amount">
                {item.count > 1 ? `×${item.count}` : ""}
              </span>
              <span className="quest-complete-toast-reward-label">{item.displayName}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
