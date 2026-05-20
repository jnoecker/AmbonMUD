import { useEffect, useLayoutEffect, useRef, useState, useCallback } from "react";
import type { CombatLogMessage } from "../types";

const MESSAGE_LIFETIME_MS = 6000;
const FADE_DURATION_MS = 600;
const MAX_VISIBLE = 8;

type CombatStyle = CombatLogMessage["style"];

const FILTER_OPTIONS: { style: CombatStyle; label: string }[] = [
  { style: "damage", label: "Dmg" },
  { style: "heal", label: "Heal" },
  { style: "dodge", label: "Dodge" },
  { style: "kill", label: "Kill" },
  { style: "info", label: "Info" },
  { style: "xp", label: "XP" },
];

const ALL_STYLES = new Set<CombatStyle>(FILTER_OPTIONS.map((f) => f.style));

interface CombatLogProps {
  messages: CombatLogMessage[];
}

export function CombatLog({ messages }: CombatLogProps) {
  const [now, setNow] = useState(() => Date.now());
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const [filterOpen, setFilterOpen] = useState(false);
  const [hidden, setHidden] = useState<Set<CombatStyle>>(() => new Set());

  const toggleStyle = useCallback((style: CombatStyle) => {
    setHidden((prev) => {
      const next = new Set(prev);
      if (next.has(style)) next.delete(style);
      else next.add(style);
      return next;
    });
  }, []);

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

  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages.length]);

  const visible = messages
    .filter((m) => now - m.receivedAt < MESSAGE_LIFETIME_MS + FADE_DURATION_MS)
    .filter((m) => !hidden.has(m.style))
    .slice(-MAX_VISIBLE);

  const hasFilters = hidden.size > 0;
  const allHidden = hidden.size >= ALL_STYLES.size;

  return (
    <div className="combat-log-wrap">
      <button
        type="button"
        className={`combat-log-filter-toggle${hasFilters ? " combat-log-filter-toggle-active" : ""}`}
        onClick={() => setFilterOpen((p) => !p)}
        title="Filter combat log"
        aria-expanded={filterOpen}
        aria-label="Toggle combat log filters"
      >
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M1 3h14M4 8h8M6 13h4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      </button>

      {filterOpen && (
        <div className="combat-log-filters" role="group" aria-label="Combat log filters">
          {FILTER_OPTIONS.map((opt) => {
            const active = !hidden.has(opt.style);
            return (
              <button
                key={opt.style}
                type="button"
                className={`combat-log-filter-chip combat-log-filter-chip-${opt.style}${active ? " combat-log-filter-chip-on" : ""}`}
                onClick={() => toggleStyle(opt.style)}
                aria-pressed={active}
              >
                {opt.label}
              </button>
            );
          })}
        </div>
      )}

      {!allHidden && visible.length > 0 && (
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
      )}
    </div>
  );
}
