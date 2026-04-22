import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { CombatLogMessage } from "../../types";

type CombatStyle = CombatLogMessage["style"];

interface CombatLogPanelProps {
  messages: CombatLogMessage[];
  onClearLog: () => void;
}

const FILTER_OPTIONS: { style: CombatStyle; label: string }[] = [
  { style: "damage", label: "Damage" },
  { style: "heal", label: "Heal" },
  { style: "dodge", label: "Dodge" },
  { style: "kill", label: "Kills" },
  { style: "xp", label: "XP" },
  { style: "info", label: "Info" },
  { style: "error", label: "Errors" },
];

// How close to the bottom (in px) counts as "stuck to bottom".
const STICKY_THRESHOLD = 24;

function formatRelativeTime(receivedAt: number, now: number): string {
  const ageMs = Math.max(0, now - receivedAt);
  if (ageMs < 5000) return "just now";
  if (ageMs < 60_000) return `${Math.floor(ageMs / 1000)}s ago`;
  const minutes = Math.floor(ageMs / 60_000);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

export function CombatLogPanel({ messages, onClearLog }: CombatLogPanelProps) {
  const [now, setNow] = useState(() => Date.now());
  const [hidden, setHidden] = useState<Set<CombatStyle>>(() => new Set());
  const scrollRef = useRef<HTMLDivElement | null>(null);
  // Start sticky so the newest entries are visible when the panel opens.
  const stickyRef = useRef<boolean>(true);

  // Tick the clock once per second to keep relative timestamps fresh.
  useEffect(() => {
    const interval = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(interval);
  }, []);

  const toggleStyle = useCallback((style: CombatStyle) => {
    setHidden((prev) => {
      const next = new Set(prev);
      if (next.has(style)) next.delete(style);
      else next.add(style);
      return next;
    });
  }, []);

  const visible = useMemo(
    () => messages.filter((m) => !hidden.has(m.style)),
    [messages, hidden],
  );

  // Track whether the user is stuck at the bottom so new messages auto-scroll
  // only when they haven't scrolled up to read something.
  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    stickyRef.current = distanceFromBottom <= STICKY_THRESHOLD;
  }, []);

  // On mount (panel open): scroll to newest.
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
    stickyRef.current = true;
  }, []);

  // When a new entry arrives and user is stuck to bottom, keep following.
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (stickyRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [visible.length]);

  const hasMessages = messages.length > 0;
  const hiddenCount = messages.length - visible.length;

  return (
    <article className="subpanel combat-log-panel" aria-label="Combat log">
      <header className="combat-log-panel-header">
        <div className="combat-log-panel-title">
          <h3>Combat Log</h3>
          <span className="combat-log-panel-count">
            {hasMessages
              ? `${messages.length} entr${messages.length === 1 ? "y" : "ies"}${hiddenCount > 0 ? ` (${hiddenCount} filtered)` : ""}`
              : "No activity yet"}
          </span>
        </div>
        <div className="combat-log-panel-actions">
          <button
            type="button"
            className="soft-button combat-log-panel-clear"
            onClick={onClearLog}
            disabled={!hasMessages}
            aria-label="Clear combat log"
          >
            Clear
          </button>
        </div>
      </header>

      <div className="combat-log-panel-filters" role="group" aria-label="Combat log filters">
        {FILTER_OPTIONS.map((opt) => {
          const active = !hidden.has(opt.style);
          return (
            <button
              key={opt.style}
              type="button"
              className={`combat-log-panel-chip combat-log-panel-chip-${opt.style}${active ? " combat-log-panel-chip-on" : ""}`}
              onClick={() => toggleStyle(opt.style)}
              aria-pressed={active}
            >
              {opt.label}
            </button>
          );
        })}
      </div>

      {!hasMessages && (
        <div className="combat-log-panel-empty">
          <p>Your combat log is empty.</p>
          <p className="combat-log-panel-empty-hint">
            Attacks, dodges, heals, kills, and XP gains will appear here as you fight.
          </p>
        </div>
      )}

      {hasMessages && visible.length === 0 && (
        <div className="combat-log-panel-empty">
          <p>All entries are hidden by filters.</p>
        </div>
      )}

      {visible.length > 0 && (
        <div
          ref={scrollRef}
          className="combat-log-panel-scroll"
          onScroll={handleScroll}
          role="log"
          aria-live="polite"
          aria-label="Combat log entries"
        >
          {visible.map((msg) => (
            <div
              key={msg.id}
              className={`combat-log-panel-row combat-log-panel-row-${msg.style}`}
            >
              <time
                className="combat-log-panel-row-time"
                dateTime={new Date(msg.receivedAt).toISOString()}
                title={new Date(msg.receivedAt).toLocaleTimeString()}
              >
                {formatRelativeTime(msg.receivedAt, now)}
              </time>
              <span className="combat-log-panel-row-text">{msg.text}</span>
            </div>
          ))}
        </div>
      )}
    </article>
  );
}
