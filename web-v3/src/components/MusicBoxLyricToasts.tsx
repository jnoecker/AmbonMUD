import { useEffect, useRef, useState } from "react";
import type { MusicBoxState } from "../types";

interface MusicBoxLyricToastsProps {
  musicBox: MusicBoxState | null;
  /** True while the music-box device drawer is open — it scrolls the lyrics itself,
   *  so the floating toasts step aside to avoid showing each line twice. */
  suppressed: boolean;
}

interface LyricToast {
  id: number;
  text: string;
}

/**
 * Floating 🎵 lyric toasts for a playing music box while its device is closed. As
 * each lyric line comes due (timed client-side from the song's start, duration, and
 * line count — the same spread the open device uses), it pops as a brief toast and
 * fades after roughly one-and-a-half line intervals, so an exploring player sees at
 * most one or two at a time and can keep up with the song without opening the box.
 */
export function MusicBoxLyricToasts({ musicBox, suppressed }: MusicBoxLyricToastsProps) {
  const np = musicBox?.nowPlaying ?? null;
  const [toasts, setToasts] = useState<LyricToast[]>([]);
  const lastIndexRef = useRef<number>(-1);
  const songKeyRef = useRef<string | null>(null);
  const idRef = useRef(0);

  // Stable per-song identity (survives MusicBox.Info re-emissions for the same song).
  const songKey = np ? `${np.roomId ?? ""}:${np.startedAtMs}` : null;

  useEffect(() => {
    // Reset the played-line cursor only on a genuine song change (not on every
    // MusicBox.Info re-emission). Any stale toasts fade out via their own timers.
    if (songKeyRef.current !== songKey) {
      songKeyRef.current = songKey;
      lastIndexRef.current = -1;
    }
    if (!np || suppressed || np.lyrics.length === 0 || np.durationSeconds <= 0) return;
    const lyrics = np.lyrics;
    const lineMs = (np.durationSeconds * 1000) / lyrics.length;
    const anchorMs = np.receivedAt - Math.max(0, np.durationSeconds - np.secondsRemaining) * 1000;
    const timer = window.setInterval(() => {
      const elapsed = Date.now() - anchorMs;
      const idx = Math.min(lyrics.length - 1, Math.floor(elapsed / lineMs));
      if (idx >= 0 && idx > lastIndexRef.current) {
        lastIndexRef.current = idx;
        const id = ++idRef.current;
        setToasts((cur) => [...cur, { id, text: lyrics[idx] }]);
        // On screen ~1.6 line intervals (clamped) so at most one or two overlap.
        const ttl = Math.min(6000, Math.max(2200, lineMs * 1.6));
        window.setTimeout(() => setToasts((cur) => cur.filter((t) => t.id !== id)), ttl);
      }
    }, 250);
    return () => window.clearInterval(timer);
  }, [np, suppressed, songKey]);

  if (toasts.length === 0) return null;
  return (
    <div className="musicbox-toasts" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className="musicbox-toast">
          <span className="musicbox-toast-note" aria-hidden="true">🎵</span>
          <span className="musicbox-toast-text">{t.text}</span>
        </div>
      ))}
    </div>
  );
}
