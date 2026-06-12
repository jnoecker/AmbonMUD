import { useEffect, useMemo, useState } from "react";
import { useMediaFits } from "../../canvas/loginArtFit";
import type { JukeboxState, UiFeedbackEntry } from "../../types";

interface JukeboxPanelProps {
  jukebox: JukeboxState | null;
  gold: number;
  /** This player's character name — the current track's buyer can't also queue the next. */
  selfName: string;
  uiFeedbackFeed: UiFeedbackEntry[];
  assets: Record<string, string>;
  onCommand: (command: string) => void;
}

/** Songs per page — one per painted scroll: the landscape frame has 4 song
 *  scrolls under the now-playing banner, the phone-portrait frame has 6. */
const LANDSCAPE_PAGE_SIZE = 4;
const PORTRAIT_PAGE_SIZE = 6;

function formatDuration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${(s % 60).toString().padStart(2, "0")}`;
}

/**
 * Room jukebox. Lists the room's playlist four songs to a page (each seated on a
 * painted scroll when skinned); the player pays a few gold to play a song, which
 * becomes the room's music for everyone until it ends. While a track plays the
 * box is locked, but anyone except the track's buyer can pay to queue the single
 * next-up slot — the bottom-right plaque shows the queued song's number.
 */
export function JukeboxPanel({ jukebox, gold, selfName, uiFeedbackFeed, assets, onCommand }: JukeboxPanelProps) {
  const [now, setNow] = useState(() => Date.now());
  const [page, setPage] = useState(0);

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
  const queued = jukebox?.queued ?? null;
  const remaining = nowPlaying
    ? Math.max(0, nowPlaying.secondsRemaining - Math.floor((now - nowPlaying.receivedAt) / 1000))
    : 0;
  const busy = nowPlaying !== null && remaining > 0;
  const mineIsPlaying = busy && nowPlaying?.buyer === selfName;

  const portraitViewport = useMediaFits("(orientation: portrait)");
  const pageSize = portraitViewport ? PORTRAIT_PAGE_SIZE : LANDSCAPE_PAGE_SIZE;
  const pages = Math.max(1, Math.ceil(songs.length / pageSize));
  const safePage = Math.min(page, pages - 1);
  const pageSongs = songs.slice(safePage * pageSize, safePage * pageSize + pageSize);

  const skinned = Boolean(assets.jukebox_bg);

  return (
    <div className={`jukebox-panel${skinned ? " jukebox-panel-skinned" : ""}`}>
      <div className="jukebox-nowplaying">
        {nowPlaying && busy ? (
          <>
            <span className="jukebox-np-eq" aria-hidden="true"><i /><i /><i /></span>
            <div className="jukebox-np-text">
              <div className="jukebox-np-title" aria-live="polite">
                {nowPlaying.title}
                {nowPlaying.artist ? <span className="jukebox-np-artist"> — {nowPlaying.artist}</span> : null}
              </div>
              <div className="jukebox-np-sub">
                Played by {nowPlaying.buyer} · {formatDuration(remaining)} left
              </div>
              {queued ? (
                <div className="jukebox-np-next">Up next: “{queued.title}” — queued by {queued.buyer}</div>
              ) : nowPlaying.description ? (
                <div className="jukebox-np-desc">{nowPlaying.description}</div>
              ) : null}
            </div>
          </>
        ) : (
          <div className="jukebox-np-text">
            <div className="jukebox-np-title" aria-live="polite">The jukebox sits quiet.</div>
            <div className="jukebox-np-sub">Drop a coin to set the room’s music.</div>
          </div>
        )}
      </div>

      {songs.length === 0 ? (
        <p className="jukebox-empty">This jukebox has no songs loaded.</p>
      ) : (
        <ul className="jukebox-list">
          {pageSongs.map((song) => {
            const canAfford = gold >= song.cost;
            const playing = busy && nowPlaying?.number === song.number;
            const isQueued = queued !== null && queued.number === song.number;
            const verb = busy ? "queue" : "play";
            const actionable = !playing && !isQueued && !(busy && (mineIsPlaying || queued !== null));
            const label = playing ? "Playing" : isQueued ? "Queued" : busy ? "Queue" : "Play";
            const tooltip = playing
              ? "This song is playing now"
              : isQueued
                ? `Queued next by ${queued?.buyer ?? "someone"}`
                : busy && mineIsPlaying
                  ? "Your song is playing — someone else gets to queue the next one"
                  : busy && queued !== null
                    ? "A song is already queued next"
                    : !canAfford
                      ? "Not enough gold"
                      : busy
                        ? `Queue "${song.title}" to play next`
                        : `Play "${song.title}" for everyone`;
            return (
              <li
                key={song.number}
                className={`jukebox-song${playing ? " is-playing" : ""}${isQueued ? " is-queued" : ""}`}
              >
                <span className="jukebox-song-num" aria-hidden="true">{song.number}</span>
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
                    disabled={!actionable || !canAfford}
                    title={tooltip}
                    onClick={() => onCommand(`jukebox ${verb} ${song.number}`)}
                  >
                    {label}
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {pages > 1 && (
        <div className="jukebox-pager">
          <button
            type="button"
            className="jukebox-pager-btn"
            onClick={() => setPage(Math.max(0, safePage - 1))}
            disabled={safePage === 0}
            aria-label="Previous songs"
          >
            ‹
          </button>
          <span className="jukebox-pager-info">{safePage + 1} / {pages}</span>
          <button
            type="button"
            className="jukebox-pager-btn"
            onClick={() => setPage(Math.min(pages - 1, safePage + 1))}
            disabled={safePage === pages - 1}
            aria-label="More songs"
          >
            ›
          </button>
        </div>
      )}

      {activeFeedback && (
        <p className={`jukebox-feedback jukebox-feedback-${activeFeedback.type}`}>{activeFeedback.message}</p>
      )}

      {/* Seated on the painted corner plaque when skinned; hidden in the flow layout
          (the banner's "Up next" line carries the same information). */}
      <div className="jukebox-next-plaque" aria-hidden="true">
        <span className="jukebox-next-label">Up next</span>
        <span className="jukebox-next-num">{queued ? `#${queued.number}` : "—"}</span>
      </div>
    </div>
  );
}
