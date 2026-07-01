import { useEffect, useState } from "react";

/**
 * Returns `Date.now()`, re-rendering every [intervalMs] so relative timestamps
 * and countdowns stay fresh. This is the canonical "tick the clock" pattern:
 * a panel that showed `const [now, setNow] = useState(() => Date.now())` plus a
 * bare mount interval can collapse to `const now = useTickingClock(ms)`.
 *
 * Only for the unconditional case — panels that gate the interval on a
 * condition (e.g. only while a cooldown is running) keep their own effect.
 */
export function useTickingClock(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);
  return now;
}
