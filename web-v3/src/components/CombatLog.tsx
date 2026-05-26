import { useEffect, useState } from "react";
import type { CombatLogMessage } from "../types";

const MESSAGE_LIFETIME_MS = 6000;
const FADE_DURATION_MS = 600;
// Only the last round or two — the full history lives in the Combat Log drawer.
const MAX_VISIBLE = 2;

interface CombatLogProps {
  messages: CombatLogMessage[];
}

/**
 * Transient on-screen combat feed, centered above the fight. Shows just the
 * most recent line or two; players open the persistent Combat Log panel to
 * review more.
 */
export function CombatLog({ messages }: CombatLogProps) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (messages.length === 0) return;
    const latest = messages[messages.length - 1];
    const ttl = Math.max(0, latest.receivedAt + MESSAGE_LIFETIME_MS + FADE_DURATION_MS - Date.now() + 200);
    const interval = window.setInterval(() => setNow(Date.now()), 200);
    const timeout = window.setTimeout(() => {
      window.clearInterval(interval);
      setNow(Date.now());
    }, ttl);
    return () => {
      window.clearInterval(interval);
      window.clearTimeout(timeout);
    };
  }, [messages]);

  const visible = messages
    .filter((m) => now - m.receivedAt < MESSAGE_LIFETIME_MS + FADE_DURATION_MS)
    .slice(-MAX_VISIBLE);

  if (visible.length === 0) return null;

  return (
    <div className="combat-log-wrap">
      <div className="combat-log" aria-live="polite" aria-label="Combat log">
        {visible.map((msg) => {
          const age = now - msg.receivedAt;
          const fading = age > MESSAGE_LIFETIME_MS;
          const opacity = fading
            ? Math.max(0, 1 - (age - MESSAGE_LIFETIME_MS) / FADE_DURATION_MS)
            : 1;

          return (
            <div
              key={msg.id}
              className={`combat-log-msg combat-log-msg-${msg.style}`}
              style={{ opacity }}
            >
              {msg.text}
            </div>
          );
        })}
      </div>
    </div>
  );
}
