import type { JukeboxState, MusicBoxState } from "../types";

/**
 * One playing song that can surface floating lyric toasts: a player's music box
 * or the room's jukebox. Lyric timing is reconstructed from the client's own
 * [receivedAt] and the [secondsRemaining] countdown (not the server clock), so it
 * stays right despite server/client clock skew; [key] is a stable per-song
 * identity (it changes when the song changes, which resets the line cursor).
 */
export interface LyricSource {
  key: string;
  lyrics: string[];
  durationSeconds: number;
  secondsRemaining: number;
  /** Client clock (ms) when the now-playing packet arrived. */
  receivedAt: number;
  /** True while this song's own device drawer is open (it scrolls the lyrics itself). */
  suppressed?: boolean;
}

/**
 * Collects the currently-playing songs — the player's music box and the room's
 * jukebox — into toast sources. [openPanel] is the drawer panel currently open, so
 * the music box's toasts step aside while its own device drawer shows the
 * scrolling lyrics; the jukebox panel doesn't scroll lyrics, so its toasts stay up.
 */
export function buildLyricSources(
  musicBox: MusicBoxState | null,
  jukebox: JukeboxState | null,
  openPanel: string | null,
): LyricSource[] {
  const sources: LyricSource[] = [];
  const mb = musicBox?.nowPlaying;
  if (mb) {
    sources.push({
      key: `mb:${mb.roomId ?? ""}:${mb.startedAtMs}`,
      lyrics: mb.lyrics,
      durationSeconds: mb.durationSeconds,
      secondsRemaining: mb.secondsRemaining,
      receivedAt: mb.receivedAt,
      suppressed: openPanel === "musicbox",
    });
  }
  const jb = jukebox?.nowPlaying;
  if (jb) {
    sources.push({
      key: `jb:${jb.startedAtMs}:${jb.number}`,
      lyrics: jb.lyrics,
      durationSeconds: jb.durationSeconds,
      secondsRemaining: jb.secondsRemaining,
      receivedAt: jb.receivedAt,
    });
  }
  return sources;
}
