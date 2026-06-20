import { useEffect, useState } from "react";
import type { RacialProcNotification } from "../types";

interface RacialProcToastProps {
  notifications: RacialProcNotification[];
  onDismiss: (id: string) => void;
}

/**
 * Top-right toast surfaced when a racial passive cheats death (a lethal-blow
 * save). Mirrors CombatVictoryToast / FleeToast — only the most recent save is
 * rendered, and it shares the corner slot with them (useGameState clears the
 * other lists when one fires). Distinct mythic color/icon so it reads as a
 * race-defining moment, not a kill or a flee.
 */
export function RacialProcToast({ notifications, onDismiss }: RacialProcToastProps) {
  const current = notifications.length > 0 ? notifications[notifications.length - 1] : null;
  if (!current) return null;
  return (
    <RacialProcToastInner
      key={current.id}
      notification={current}
      onDismiss={() => onDismiss(current.id)}
    />
  );
}

interface InnerProps {
  notification: RacialProcNotification;
  onDismiss: () => void;
}

function RacialProcToastInner({ notification, onDismiss }: InnerProps) {
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

  const { abilityName, text } = notification;

  return (
    <div
      className={`racial-proc-toast${closing ? " racial-proc-toast-closing" : ""}`}
      role="status"
      aria-live="polite"
      onClick={() => setClosing(true)}
    >
      <div className="racial-proc-toast-header">
        <span className="racial-proc-toast-icon" aria-hidden="true">{"✦"}</span>
        <div className="racial-proc-toast-titles">
          <span className="racial-proc-toast-eyebrow">Cheated death</span>
          <span className="racial-proc-toast-name">{abilityName}</span>
        </div>
      </div>
      <p className="racial-proc-toast-text">{text}</p>
    </div>
  );
}
