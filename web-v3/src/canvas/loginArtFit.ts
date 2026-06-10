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
