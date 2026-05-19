import { useEffect, useState } from "react";
import type { FleeNotification } from "../types";

interface FleeToastProps {
  notifications: FleeNotification[];
  onDismiss: (id: string) => void;
}

/**
 * Top-right toast surfaced when the local player disengages from combat. Mirrors
 * CombatVictoryToast — only the most recent flee is rendered, back-to-back
 * flees replace rather than stack. Distinct color/icon so it doesn't read as
 * a victory.
 */
export function FleeToast({ notifications, onDismiss }: FleeToastProps) {
  const current = notifications.length > 0 ? notifications[notifications.length - 1] : null;
  if (!current) return null;
  return (
    <FleeToastInner
      key={current.id}
      notification={current}
      onDismiss={() => onDismiss(current.id)}
    />
  );
}

interface InnerProps {
  notification: FleeNotification;
  onDismiss: () => void;
}

function FleeToastInner({ notification, onDismiss }: InnerProps) {
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

  const { targetName, forced } = notification;
  const eyebrow = forced ? "Forced to flee" : "Escaped";
  const subtitle = forced
    ? `Wounds drove you away from ${targetName}`
    : `You fled from ${targetName}`;

  return (
    <div
      className={`flee-toast${forced ? " flee-toast-forced" : ""}${closing ? " flee-toast-closing" : ""}`}
      role="status"
      aria-live="polite"
      onClick={() => setClosing(true)}
    >
      <div className="flee-toast-header">
        <span className="flee-toast-icon" aria-hidden="true">{forced ? "☠" : "👟"}</span>
        <div className="flee-toast-titles">
          <span className="flee-toast-eyebrow">{eyebrow}</span>
          <span className="flee-toast-name">{subtitle}</span>
        </div>
      </div>
    </div>
  );
}
