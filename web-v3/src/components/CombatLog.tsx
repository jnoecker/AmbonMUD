import { useEffect, useRef, useState } from "react";
import type { CombatLogMessage } from "../types";

const MESSAGE_LIFETIME_MS = 6000;
const FADE_DURATION_MS = 600;
const MAX_VISIBLE = 8;

interface CombatLogProps {
  messages: CombatLogMessage[];
}

export function CombatLog({ messages }: CombatLogProps) {
  const [now, setNow] = useState(() => Date.now());
  const scrollRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (messages.length === 0) return;
    const interval = window.setInterval(() => setNow(Date.now()), 200);
    return () => window.clearInterval(interval);
  }, [messages.length]);

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages.length]);

  const visible = messages
    .filter((m) => now - m.receivedAt < MESSAGE_LIFETIME_MS + FADE_DURATION_MS)
    .slice(-MAX_VISIBLE);

  if (visible.length === 0) return null;

  return (
    <div className="combat-log" ref={scrollRef} aria-live="polite" aria-label="Combat log">
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
  );
}
