import { useEffect, useRef, useState } from "react";
import type { LyricSource } from "./lyricSources";

interface LyricToast {
  id: number;
  text: string;
}

/**
 * Floating 🎵 lyric toasts, shown top-center over the canvas, for any songs
 * currently playing (the player's music box and/or the room's jukebox). As each
 * lyric line comes due — timed client-side from the song's start, duration, and
 * line count — it pops as a brief toast and fades after roughly one-and-a-half
 * line intervals, so an exploring player sees at most one or two at a time and can
 * keep up without opening the device. A source with its device drawer open is
 * suppressed (the drawer scrolls the lyrics itself), avoiding showing each line
 * twice.
 */
export function LyricToasts({ sources }: { sources: LyricSource[] }) {
  const [toasts, setToasts] = useState<LyricToast[]>([]);
  // Last played line index, per song key — survives re-emissions for the same song.
  const lastIndexRef = useRef<Map<string, number>>(new Map());
  const idRef = useRef(0);

  // Identity signature: re-run the timer only when the set of songs (or their
  // timing anchors / suppression) actually changes, not on every render.
  const sig = sources
    .map((s) => `${s.key}@${s.receivedAt}:${s.secondsRemaining}#${s.suppressed ? 1 : 0}`)
    .join("|");

  useEffect(() => {
    // Forget cursors for songs that are no longer playing; any stale toasts fade
    // out on their own timers.
    const liveKeys = new Set(sources.map((s) => s.key));
    for (const k of [...lastIndexRef.current.keys()]) {
      if (!liveKeys.has(k)) lastIndexRef.current.delete(k);
    }
    const active = sources.filter(
      (s) => !s.suppressed && s.lyrics.length > 0 && s.durationSeconds > 0,
    );
    if (active.length === 0) return;
    const timer = window.setInterval(() => {
      for (const s of active) {
        const lineMs = (s.durationSeconds * 1000) / s.lyrics.length;
        const anchorMs = s.receivedAt - Math.max(0, s.durationSeconds - s.secondsRemaining) * 1000;
        const elapsed = Date.now() - anchorMs;
        const idx = Math.min(s.lyrics.length - 1, Math.floor(elapsed / lineMs));
        const last = lastIndexRef.current.get(s.key) ?? -1;
        if (idx >= 0 && idx > last) {
          lastIndexRef.current.set(s.key, idx);
          const id = ++idRef.current;
          const text = s.lyrics[idx];
          setToasts((cur) => [...cur, { id, text }]);
          // On screen ~1.6 line intervals (clamped) so at most one or two overlap.
          const ttl = Math.min(6000, Math.max(2200, lineMs * 1.6));
          window.setTimeout(() => setToasts((cur) => cur.filter((t) => t.id !== id)), ttl);
        }
      }
    }, 250);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig]);

  if (toasts.length === 0) return null;
  return (
    <div className="lyric-toasts" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className="lyric-toast">
          <span className="lyric-toast-note" aria-hidden="true">🎵</span>
          <span className="lyric-toast-text">{t.text}</span>
        </div>
      ))}
    </div>
  );
}
