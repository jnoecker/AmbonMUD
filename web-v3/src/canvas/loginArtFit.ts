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
