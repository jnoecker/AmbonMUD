import { useEffect, useMemo, useState } from "react";
import type { MusicBoxState, UiFeedbackEntry } from "../../types";

interface MusicBoxPanelProps {
  musicBox: MusicBoxState | null;
  uiFeedbackFeed: UiFeedbackEntry[];
  assets: Record<string, string>;
  onCommand: (command: string) => void;
}

function formatDuration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${(s % 60).toString().padStart(2, "0")}`;
}

/**
 * The music box: a one-song miniature of the jukebox. Winding it up (Play) is free
 * and personal — the song plays only for you and follows you out of the room until
 * it ends or you Stop it. The central parchment scrolls the song's lyrics, timed
 * client-side from the song's start, duration, and line count (lines are spread
 * evenly across the duration), the current line lit on the cylinder's face.
 */
export function MusicBoxPanel({ musicBox, uiFeedbackFeed, assets, onCommand }: MusicBoxPanelProps) {
  // 250ms tick keeps the highlighted lyric line roughly in step with the music
  // without the cost of an animation frame.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, []);

  const activeFeedback = useMemo(
    () => [...uiFeedbackFeed].reverse().find((e) => e.scope === "musicbox") ?? null,
    [uiFeedbackFeed],
  );

  const box = musicBox?.box ?? null;
  const nowPlaying = musicBox?.nowPlaying ?? null;
  const playing = nowPlaying !== null;

  // Show the playing song if there is one, otherwise the room box's offering.
  const title = nowPlaying?.title ?? box?.title ?? "Music Box";
  const artist = nowPlaying?.artist ?? box?.artist ?? null;
  const description = nowPlaying?.description ?? box?.description ?? null;
  const lyrics = nowPlaying?.lyrics ?? box?.lyrics ?? [];
  const duration = nowPlaying?.durationSeconds ?? box?.durationSeconds ?? 0;

  // Client-side lyric timing: which line is "current" right now, and how long left.
  // Anchor to the client clock (receivedAt + countdown) rather than the server's
  // startedAtMs so clock skew can't drift the scroll out of step with the audio.
  const anchorMs = playing
    ? nowPlaying.receivedAt - Math.max(0, nowPlaying.durationSeconds - nowPlaying.secondsRemaining) * 1000
    : 0;
  const elapsedMs = playing ? Math.max(0, now - anchorMs) : 0;
  const lineMs = lyrics.length > 0 && duration > 0 ? (duration * 1000) / lyrics.length : 0;
  const currentIndex =
    playing && lineMs > 0 ? Math.min(lyrics.length - 1, Math.floor(elapsedMs / lineMs)) : -1;
  const remaining = playing ? Math.max(0, duration - Math.floor(elapsedMs / 1000)) : 0;

  const skinned = Boolean(assets.musicbox_bg);

  return (
    <div className={`musicbox-panel${skinned ? " musicbox-panel-skinned" : ""}`}>
      {/* Title cartouche — the painted parchment label at the top of the box. */}
      <div className="musicbox-title">
        <span className="musicbox-title-name">{title}</span>
        {artist ? <span className="musicbox-title-artist"> — {artist}</span> : null}
      </div>

      {/* Central parchment: the lyrics scroll here, current line lit. */}
      <div className="musicbox-parchment">
        {lyrics.length === 0 ? (
          <p className="musicbox-no-lyrics">
            {playing ? "This song plays without words." : description ?? "Wind it up to hear its song."}
          </p>
        ) : (
          <div className="musicbox-lyrics-viewport">
            <ol
              className="musicbox-lyrics"
              style={{ transform: `translateY(calc(50% - ${Math.max(0, currentIndex) * 2.1}em))` }}
            >
              {lyrics.map((line, i) => (
                <li
                  key={i}
                  className={
                    "musicbox-lyric" +
                    (i === currentIndex ? " is-current" : i < currentIndex ? " is-past" : " is-future")
                  }
                >
                  {line}
                </li>
              ))}
            </ol>
          </div>
        )}
      </div>

      {/* Play / Stop crank and countdown. */}
      <div className="musicbox-controls">
        {playing ? (
          <button
            type="button"
            className="musicbox-btn musicbox-btn-stop"
            onClick={() => onCommand("musicbox stop")}
            title="Close the music box and stop the song"
          >
            ◼ Stop
          </button>
        ) : (
          <button
            type="button"
            className="musicbox-btn musicbox-btn-play"
            disabled={!box}
            onClick={() => onCommand("musicbox play")}
            title={box ? "Wind up the music box — it'll follow you until it ends" : "There is no music box here"}
          >
            ▶ Play
          </button>
        )}
        {playing ? (
          <span className="musicbox-remaining" aria-live="polite">{formatDuration(remaining)} left</span>
        ) : box ? (
          <span className="musicbox-remaining musicbox-remaining-idle">{formatDuration(duration)}</span>
        ) : null}
      </div>

      {activeFeedback && (
        <p className={`musicbox-feedback musicbox-feedback-${activeFeedback.type}`}>{activeFeedback.message}</p>
      )}
    </div>
  );
}
