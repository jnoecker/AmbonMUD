import { useEffect, useState } from "react";

/**
 * Viewport gates for the painted login scenes. Below these the mapped painted
 * controls get too small to use — components fall back to the CSS UI instead.
 * The portrait race/class scenes are phone-tuned and use the looser query.
 */
export const ART_FIT_LANDSCAPE = "(min-width: 720px) and (min-height: 460px)";
export const ART_FIT_PORTRAIT = "(min-width: 320px) and (min-height: 480px)";

export function useMediaFits(query: string): boolean {
  const [fits, setFits] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const mql = window.matchMedia(query);
    const onChange = () => setFits(mql.matches);
    mql.addEventListener("change", onChange);
    return () => mql.removeEventListener("change", onChange);
  }, [query]);
  return fits;
}

const ORIENTATION_PORTRAIT = "(orientation: portrait)";

/**
 * Pick between a landscape scene and its phone-portrait companion by viewport
 * orientation, falling back to whichever fits when the preferred one is
 * absent, and gating the winner on the image actually loading. `phone` is
 * true when the portrait companion (941×1672 stage) was selected.
 */
export function useOrientedArt(
  landscapeUrl: string | null,
  portraitUrl: string | null,
): { url: string | null; phone: boolean } {
  const fitsLandscape = useMediaFits(ART_FIT_LANDSCAPE);
  const fitsPortrait = useMediaFits(ART_FIT_PORTRAIT);
  const portraitViewport = useMediaFits(ORIENTATION_PORTRAIT);
  const landscape = fitsLandscape ? landscapeUrl : null;
  const portrait = fitsPortrait ? portraitUrl : null;
  const wanted = portraitViewport ? (portrait ?? landscape) : (landscape ?? portrait);
  const url = useArtImage(wanted);
  return { url, phone: url !== null && wanted === portrait };
}

/** Module-level result cache: each art URL is probed at most once per session. */
const artImageStatus = new Map<string, "ready" | "failed">();

/**
 * Gate a painted-scene URL on the image actually loading. For full-scene art
 * the background is load-bearing — rendering the painted variant against a URL
 * that 404s, is blocked by an extension, or whose CDN is unreachable produces
 * a black screen with floating controls. Returns the URL only once the image
 * has decoded; on failure (or while loading) returns null so callers keep the
 * CSS fallback. Required by ART_CONTRACT.md's degradation rules.
 */
export function useArtImage(url: string | null): string | null {
  const [, setProbedCount] = useState(0);
  // Synchronous HTTP-cache seed: when the art is already cached (warmed by the
  // preloads in main.tsx / App.tsx), a freshly-constructed Image reports
  // `complete` with a non-zero natural size the instant we assign src. Marking
  // it ready here — before the first paint — lets a warm scene render painted
  // immediately instead of flashing the CSS fallback for the frame it takes the
  // async onload below to settle. Cold art falls through to that async probe.
  if (url && !artImageStatus.has(url)) {
    const probe = new Image();
    probe.src = url;
    if (probe.complete && probe.naturalWidth > 0) artImageStatus.set(url, "ready");
  }
  useEffect(() => {
    if (!url || artImageStatus.has(url)) return;
    let cancelled = false;
    const img = new Image();
    const settle = (status: "ready" | "failed") => {
      artImageStatus.set(url, status);
      if (!cancelled) setProbedCount((n) => n + 1);
    };
    img.onload = () => settle("ready");
    img.onerror = () => settle("failed");
    img.src = url;
    return () => { cancelled = true; };
  }, [url]);
  return url && artImageStatus.get(url) === "ready" ? url : null;
}
