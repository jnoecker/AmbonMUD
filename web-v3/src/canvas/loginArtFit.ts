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
 * true when the portrait companion (941×1672 stage) was selected. `pending`
 * mirrors [useArtImageState]: true while the probe is still in flight, so
 * callers can hold a neutral frame instead of flashing their CSS fallback.
 */
export function useOrientedArt(
  landscapeUrl: string | null,
  portraitUrl: string | null,
): { url: string | null; phone: boolean; pending: boolean } {
  const fitsLandscape = useMediaFits(ART_FIT_LANDSCAPE);
  const fitsPortrait = useMediaFits(ART_FIT_PORTRAIT);
  const portraitViewport = useMediaFits(ORIENTATION_PORTRAIT);
  const landscape = fitsLandscape ? landscapeUrl : null;
  const portrait = fitsPortrait ? portraitUrl : null;
  const wanted = portraitViewport ? (portrait ?? landscape) : (landscape ?? portrait);
  const { url, pending } = useArtImageState(wanted);
  return { url, phone: url !== null && wanted === portrait, pending };
}

/** Module-level result cache: each art URL is probed at most once per session. */
const artImageStatus = new Map<string, "ready" | "failed">();

/**
 * How long callers may hold a neutral "art incoming" frame before giving up
 * and showing their CSS fallback. Warm art (HTTP or service-worker cache)
 * settles in a frame or two; this only bites on a genuinely slow cold fetch,
 * where a usable fallback UI beats a blank screen. If the art lands later it
 * still swaps in.
 */
const ART_PENDING_GRACE_MS = 2500;

/**
 * Gate a painted-scene URL on the image actually loading. For full-scene art
 * the background is load-bearing — rendering the painted variant against a URL
 * that 404s, is blocked by an extension, or whose CDN is unreachable produces
 * a black screen with floating controls. `url` is non-null only once the image
 * has decoded; on failure it stays null so callers keep the CSS fallback,
 * per ART_CONTRACT.md's degradation rules.
 *
 * `pending` is true while the probe is still in flight (bounded by
 * [ART_PENDING_GRACE_MS]). The service worker intercepts same-origin image
 * requests, which makes even cache-warm loads settle asynchronously — one or
 * two frames after first render — so callers that paint their CSS fallback
 * immediately flash it. While `pending`, render a neutral holding frame
 * instead.
 */
export function useArtImageState(url: string | null): { url: string | null; pending: boolean } {
  const [, setProbedCount] = useState(0);
  // Grace expiry is keyed to the URL it timed out on, so switching to a new
  // URL starts un-expired without needing a state reset.
  const [expiredUrl, setExpiredUrl] = useState<string | null>(null);
  // Synchronous HTTP-cache seed: when the art is already in the renderer's
  // memory cache, a freshly-constructed Image reports `complete` with a
  // non-zero natural size the instant we assign src. Marking it ready here —
  // before the first paint — lets such art render painted immediately. Art
  // served from the HTTP disk cache or through the service worker settles via
  // the async probe below instead.
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
  useEffect(() => {
    if (!url || artImageStatus.has(url)) return;
    const timer = window.setTimeout(() => setExpiredUrl(url), ART_PENDING_GRACE_MS);
    return () => window.clearTimeout(timer);
  }, [url]);
  const status = url ? artImageStatus.get(url) : undefined;
  return {
    url: url && status === "ready" ? url : null,
    pending: url !== null && status === undefined && expiredUrl !== url,
  };
}

/** [useArtImageState] without the pending signal, for decorative callers. */
export function useArtImage(url: string | null): string | null {
  return useArtImageState(url).url;
}
