import { useEffect, useMemo, useState } from "react";
import type { QuestNotification } from "../types";

interface QuestCompleteToastProps {
  notifications: QuestNotification[];
  onDismiss: (id: string) => void;
}

const EYEBROW: Record<QuestNotification["event"], string> = {
  accept: "Quest Accepted",
  update: "Quest Progress",
  ready: "Ready to Turn In",
  complete: "Quest Complete",
};

const ICON: Record<QuestNotification["event"], string> = {
  accept: "✦",
  update: "◆",
  ready: "❖",
  complete: "✨",
};

/** Progress ticks are quick cues; the milestone toasts stay up longer. */
const HOLD_MS: Record<QuestNotification["event"], number> = {
  accept: 5000,
  update: 3000,
  ready: 6000,
  complete: 5000,
};

/**
 * Top-right toast for quest lifecycle GMCP packets.
 *
 * Picks up the oldest unhandled QuestNotification — accept, objective
 * progress, ready-to-turn-in, or complete (with rewards) — shows it for a few
 * seconds, then fades. Click anywhere on the toast to dismiss early. Stays
 * out of the way of gameplay.
 */
export function QuestCompleteToast({ notifications, onDismiss }: QuestCompleteToastProps) {
  const current = useMemo(() => notifications[0] ?? null, [notifications]);
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
    const fade = window.setTimeout(() => setClosing(true), HOLD_MS[notification.event]);
    return () => window.clearTimeout(fade);
  }, [notification.event]);

  useEffect(() => {
    if (!closing) return;
    const clear = window.setTimeout(() => onDismiss(), 350);
    return () => window.clearTimeout(clear);
  }, [closing, onDismiss]);

  const rewards = notification.rewards;
  const objective = notification.objective;
  const currencyEntries = rewards ? Object.entries(rewards.currencies) : [];
  const itemEntries = rewards?.items ?? [];
  const hasRewards =
    !!rewards &&
    (rewards.xp > 0 || rewards.gold > 0 || currencyEntries.length > 0 || itemEntries.length > 0);

  return (
    <div
      className={`quest-complete-toast quest-complete-toast-${notification.event}${closing ? " quest-complete-toast-closing" : ""}`}
      role="status"
      aria-live="polite"
      onClick={() => setClosing(true)}
    >
      <div className="quest-complete-toast-header">
        <span className="quest-complete-toast-icon" aria-hidden="true">{ICON[notification.event]}</span>
        <div className="quest-complete-toast-titles">
          <span className="quest-complete-toast-eyebrow">{EYEBROW[notification.event]}</span>
          <span className="quest-complete-toast-name">{notification.questName}</span>
        </div>
      </div>
      {notification.questDescription && (
        <p className="quest-complete-toast-desc">{notification.questDescription}</p>
      )}
      {objective && (
        <p className="quest-complete-toast-desc quest-complete-toast-objective">
          <span className="quest-complete-toast-objective-text">{objective.description}</span>
          <span className="quest-complete-toast-objective-count">
            {objective.current >= objective.required ? "done" : `${objective.current}/${objective.required}`}
          </span>
        </p>
      )}
      {notification.event === "ready" && (
        <p className="quest-complete-toast-desc quest-complete-toast-hint">Return to the quest-giver to turn it in.</p>
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
