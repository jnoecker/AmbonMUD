import { useEffect, useMemo, useState } from "react";
import type { JukeboxState, UiFeedbackEntry } from "../../types";

interface JukeboxPanelProps {
  jukebox: JukeboxState | null;
  gold: number;
  uiFeedbackFeed: UiFeedbackEntry[];
  assets: Record<string, string>;
  onCommand: (command: string) => void;
}

function formatDuration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${(s % 60).toString().padStart(2, "0")}`;
}

/**
 * Room jukebox. Lists the room's playlist; the player pays a few gold to play a
 * song, which becomes the room's music for everyone until it ends. While a track
 * is playing the jukebox is locked (every Play button disabled) and a now-playing
 * banner counts down to the revert.
 */
export function JukeboxPanel({ jukebox, gold, uiFeedbackFeed, assets, onCommand }: JukeboxPanelProps) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const activeFeedback = useMemo(
    () => [...uiFeedbackFeed].reverse().find((e) => e.scope === "jukebox") ?? null,
    [uiFeedbackFeed],
  );

  const songs = jukebox?.songs ?? [];
  const nowPlaying = jukebox?.nowPlaying ?? null;
  const remaining = nowPlaying
    ? Math.max(0, nowPlaying.secondsRemaining - Math.floor((now - nowPlaying.receivedAt) / 1000))
    : 0;
  const busy = nowPlaying !== null && remaining > 0;

  const bg = assets.jukebox_bg;
  const style = bg ? { ["--jukebox-bg" as string]: `url(${bg})` } : undefined;

  return (
    <div className={`jukebox-panel${bg ? " jukebox-panel-skinned" : ""}`} style={style}>
      {nowPlaying && (
        <div className="jukebox-nowplaying" aria-live="polite">
          <span className="jukebox-np-eq" aria-hidden="true"><i /><i /><i /></span>
          <div className="jukebox-np-text">
            <div className="jukebox-np-title">
              {nowPlaying.title}
              {nowPlaying.artist ? <span className="jukebox-np-artist"> — {nowPlaying.artist}</span> : null}
            </div>
            <div className="jukebox-np-sub">
              Queued by {nowPlaying.buyer} · {busy ? `${formatDuration(remaining)} left` : "ending…"}
            </div>
          </div>
        </div>
      )}

      {songs.length === 0 ? (
        <p className="jukebox-empty">This jukebox has no songs loaded.</p>
      ) : (
        <ul className="jukebox-list">
          {songs.map((song) => {
            const canAfford = gold >= song.cost;
            const playing = nowPlaying?.number === song.number && busy;
            return (
              <li key={song.number} className={`jukebox-song${playing ? " is-playing" : ""}`}>
                <div className="jukebox-song-main">
                  <span className="jukebox-song-title">{song.title}</span>
                  {song.artist ? <span className="jukebox-song-artist"> — {song.artist}</span> : null}
                  {song.description ? <p className="jukebox-song-desc">{song.description}</p> : null}
                </div>
                <div className="jukebox-song-meta">
                  <span className="jukebox-song-dur">{formatDuration(song.durationSeconds)}</span>
                  <span className={`jukebox-song-cost${canAfford ? "" : " is-short"}`}>{song.cost} gold</span>
                  <button
                    type="button"
                    className="jukebox-play-btn"
                    disabled={busy || !canAfford}
                    title={
                      busy
                        ? "The jukebox is busy until the current song ends"
                        : canAfford
                          ? `Play "${song.title}" for everyone`
                          : "Not enough gold"
                    }
                    onClick={() => onCommand(`jukebox play ${song.number}`)}
                  >
                    {playing ? "Playing" : "Play"}
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {activeFeedback && (
        <p className={`jukebox-feedback jukebox-feedback-${activeFeedback.type}`}>{activeFeedback.message}</p>
      )}
    </div>
  );
}
