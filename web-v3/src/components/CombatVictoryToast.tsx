import { useEffect, useState } from "react";
import type { CombatVictoryNotification } from "../types";

interface CombatVictoryToastProps {
  notifications: CombatVictoryNotification[];
  onDismiss: (id: string) => void;
}

/**
 * Top-right toast surfaced when the local player lands a killing blow.
 *
 * Shows the defeated enemy plus XP/gold/autolooted-items earned for ~4.5s
 * then fades. Click to dismiss early. Only the most recent kill is rendered;
 * back-to-back kills replace the prior toast so rapid pulls don't stack the
 * corner with N cards.
 */
export function CombatVictoryToast({ notifications, onDismiss }: CombatVictoryToastProps) {
  const current = notifications.length > 0 ? notifications[notifications.length - 1] : null;
  if (!current) return null;
  return (
    <CombatVictoryToastInner
      key={current.id}
      notification={current}
      onDismiss={() => onDismiss(current.id)}
    />
  );
}

interface InnerProps {
  notification: CombatVictoryNotification;
  onDismiss: () => void;
}

function CombatVictoryToastInner({ notification, onDismiss }: InnerProps) {
  const [closing, setClosing] = useState(false);

  useEffect(() => {
    const fade = window.setTimeout(() => setClosing(true), 4500);
    return () => window.clearTimeout(fade);
  }, []);

  useEffect(() => {
    if (!closing) return;
    const clear = window.setTimeout(() => onDismiss(), 350);
    return () => window.clearTimeout(clear);
  }, [closing, onDismiss]);

  const { targetName, xpGained, goldGained, lootedItems } = notification;
  const hasRewards = xpGained > 0 || goldGained > 0;

  return (
    <div
      className={`combat-victory-toast${closing ? " combat-victory-toast-closing" : ""}`}
      role="status"
      aria-live="polite"
      onClick={() => setClosing(true)}
    >
      <div className="combat-victory-toast-header">
        <span className="combat-victory-toast-icon" aria-hidden="true">{"⚔"}</span>
        <div className="combat-victory-toast-titles">
          <span className="combat-victory-toast-eyebrow">Victory</span>
          <span className="combat-victory-toast-name">{targetName} defeated</span>
        </div>
      </div>
      {hasRewards && (
        <ul className="combat-victory-toast-rewards" role="list">
          {xpGained > 0 && (
            <li className="combat-victory-toast-reward combat-victory-toast-reward-xp">
              <span className="combat-victory-toast-reward-amount">+{xpGained}</span>
              <span className="combat-victory-toast-reward-label">XP</span>
            </li>
          )}
          {goldGained > 0 && (
            <li className="combat-victory-toast-reward combat-victory-toast-reward-gold">
              <span className="combat-victory-toast-reward-amount">+{goldGained}</span>
              <span className="combat-victory-toast-reward-label">Gold</span>
            </li>
          )}
        </ul>
      )}
      {lootedItems.length > 0 && (
        <ul className="combat-victory-toast-loot" role="list">
          {lootedItems.map((name, i) => (
            <li key={`${name}-${i}`} className="combat-victory-toast-loot-item">
              <span className="combat-victory-toast-loot-bullet" aria-hidden="true">{"◆"}</span>
              <span className="combat-victory-toast-loot-name">{name}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
