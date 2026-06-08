import type { FeaturePopoutFocus, RoomFeature, RoomFeatureType } from "../types";

const FEATURE_ORDER: RoomFeatureType[] = ["door", "container", "lever", "sign"];

/**
 * The feature a freshly-opened panel should show. A panel is opened against a
 * specific feature (the canvas badge / room list passes its type), so we prefer
 * the first feature of that type; otherwise the first feature in display order.
 * Shared so App keeps the Drawer title + skin background in sync with the panel.
 */
export function pickFocusedFeature(
  features: RoomFeature[],
  preferred: FeaturePopoutFocus,
): RoomFeature | null {
  if (features.length === 0) return null;
  if (preferred) {
    const match = features.find((f) => f.type === preferred);
    if (match) return match;
  }
  for (const type of FEATURE_ORDER) {
    const match = features.find((f) => f.type === type);
    if (match) return match;
  }
  return features[0];
}

/**
 * Fully-resolved backdrop art URL for a feature (per-feature override → global
 * `<type>_bg` default), or null when none is shipped yet. Levers compose their
 * own art in-panel, so this is used by App for the sign/container drawer skin.
 */
export function featureArt(
  feature: RoomFeature | null,
  serverAssets: Record<string, string>,
): string | null {
  if (!feature) return null;
  return feature.backgroundImage ?? serverAssets[`${feature.type}_bg`] ?? null;
}
