import { useEffect, useState } from "react";
import type { LevelUpNotification } from "../types";

interface LevelUpBannerProps {
  notification: LevelUpNotification | null;
  /** Dismisses the banner (click-to-dismiss or auto-fade). */
  onDismiss: () => void;
}

/**
 * Full-page celebration overlay triggered by a Char.LevelUp GMCP packet.
 *
 * Visibility contract:
 *  - The notification stays rendered (with a pulsing glow) until either the
 *    player clicks/keys to dismiss OR the auto-fade timer elapses.
 *  - Milestone levels (10/25/50/100) get a longer auto-fade + extra flourish.
 *  - Honors `prefers-reduced-motion`: animations collapse to a simple fade
 *    while the dismiss affordances remain untouched.
 *
 * Gameplay is intentionally *not* blocked — the overlay uses pointer-events:
 * none on the backdrop and only the card itself is interactive, so the player
 * can keep attacking during a grind.
 *
 * The component re-keys on notification.id, which resets all internal state
 * (including the entering/closing animation state) when a new notification
 * arrives. That avoids setState-in-effect lint violations.
 */
export function LevelUpBanner({ notification, onDismiss }: LevelUpBannerProps) {
  if (!notification) return null;
  return (
    <LevelUpBannerInner
      key={notification.id}
      notification={notification}
      onDismiss={onDismiss}
    />
  );
}

interface InnerProps {
  notification: LevelUpNotification;
  onDismiss: () => void;
}

function LevelUpBannerInner({ notification, onDismiss }: InnerProps) {
  const [closing, setClosing] = useState(false);

  useEffect(() => {
    const autoFadeMs = notification.isMilestone ? 7000 : 4500;
    const fadeTimer = window.setTimeout(() => setClosing(true), autoFadeMs);
    return () => window.clearTimeout(fadeTimer);
  }, [notification.isMilestone]);

  useEffect(() => {
    if (!closing) return;
    const clearTimer = window.setTimeout(() => onDismiss(), 650);
    return () => window.clearTimeout(clearTimer);
  }, [closing, onDismiss]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape" || e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        setClosing(true);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);

  const dismiss = () => setClosing(true);
  const animClass = closing ? "level-up-banner-closing" : "level-up-banner-entering";
  const milestoneClass = notification.isMilestone ? " level-up-banner-milestone" : "";
  const headline = notification.isMilestone ? "Milestone!" : "Level Up!";
  const gainedLevels = Math.max(1, notification.levelsGained);
  const gainedLabel =
    gainedLevels > 1
      ? `${notification.previousLevel} → ${notification.newLevel} (+${gainedLevels} levels)`
      : `${notification.previousLevel} → ${notification.newLevel}`;

  return (
    <div
      className={`level-up-banner-overlay ${animClass}${milestoneClass}`}
      role="presentation"
      aria-hidden={closing ? "true" : undefined}
    >
      <div className="level-up-banner-aura" aria-hidden="true">
        <div className="level-up-banner-ring level-up-banner-ring-1" />
        <div className="level-up-banner-ring level-up-banner-ring-2" />
        <div className="level-up-banner-ring level-up-banner-ring-3" />
      </div>

      <div
        className="level-up-banner-card"
        role="alertdialog"
        aria-live="assertive"
        aria-labelledby="level-up-headline"
        onClick={dismiss}
      >
        <div className="level-up-banner-sparkles" aria-hidden="true">
          <span className="level-up-banner-spark spark-a">{"✨"}</span>
          <span className="level-up-banner-spark spark-b">{"✨"}</span>
          <span className="level-up-banner-spark spark-c">{"✨"}</span>
          <span className="level-up-banner-spark spark-d">{"✨"}</span>
        </div>

        <p className="level-up-banner-eyebrow">{headline}</p>
        <h2 id="level-up-headline" className="level-up-banner-level">
          Level <span className="level-up-banner-number">{notification.newLevel}</span>
        </h2>
        <p className="level-up-banner-transition">{gainedLabel}</p>

        <ul className="level-up-banner-rewards" role="list">
          {notification.hpGained > 0 && (
            <li className="level-up-banner-reward level-up-banner-reward-hp">
              <span className="level-up-banner-reward-amount">+{notification.hpGained}</span>
              <span className="level-up-banner-reward-label">Max HP</span>
            </li>
          )}
          {notification.manaGained > 0 && (
            <li className="level-up-banner-reward level-up-banner-reward-mana">
              <span className="level-up-banner-reward-amount">+{notification.manaGained}</span>
              <span className="level-up-banner-reward-label">Max Mana</span>
            </li>
          )}
          {notification.skillPointsAvailable > 0 && (
            <li className="level-up-banner-reward level-up-banner-reward-skill">
              <span className="level-up-banner-reward-amount">
                {notification.skillPointsAvailable}
              </span>
              <span className="level-up-banner-reward-label">
                Skill point{notification.skillPointsAvailable === 1 ? "" : "s"}
              </span>
            </li>
          )}
        </ul>

        {notification.newAbilities.length > 0 && (
          <div className="level-up-banner-abilities">
            <p className="level-up-banner-abilities-title">New abilities learned</p>
            <ul className="level-up-banner-abilities-list" role="list">
              {notification.newAbilities.map((name) => (
                <li key={name} className="level-up-banner-ability">
                  {name}
                </li>
              ))}
            </ul>
          </div>
        )}

        <button
          type="button"
          className="level-up-banner-dismiss"
          aria-label="Dismiss level up notification"
          onClick={(e) => {
            e.stopPropagation();
            dismiss();
          }}
        >
          Continue
        </button>
      </div>
    </div>
  );
}
