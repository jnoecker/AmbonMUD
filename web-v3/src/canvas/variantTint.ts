import { ColorMatrixFilter } from "pixi.js";

/** Parses a server "#rrggbb" tint into a PixiJS numeric color, or null if absent/invalid. */
export function parseHexTint(tint: string | null | undefined): number | null {
  if (!tint) return null;
  const hex = tint.replace("#", "").trim();
  if (!/^[0-9a-fA-F]{6}$/.test(hex)) return null;
  return parseInt(hex, 16);
}

/**
 * How strongly the variant recolor replaces the original art (0 = original art,
 * 1 = fully recolored). Kept moderate so the creature's shading still reads
 * through and saturated tints (verdant) recolor rather than flood. The CSS
 * bestiary wash uses the same value — keep them in sync.
 */
export const VARIANT_TINT_STRENGTH = 0.6;

/**
 * Builds a "lighten toward the tint" ColorMatrixFilter for a rare-variant tint.
 *
 * The earlier model (and PixiJS's `sprite.tint`) only ever *darkened*: a pale
 * tint like albino's near-white `#f5f0ff` left a dark creature dark — so albino
 * showed no change — while a saturated tint like verdant flooded the whole
 * sprite. This instead desaturates to luminance and *screens* the tint over it:
 *
 *   out_c = tint_c + luma · (1 − tint_c)
 *
 * which is a linear color matrix. A near-white tint lifts the creature to a
 * pale/ghostly read; a saturated tint recolors it while keeping its shading.
 * `filter.alpha` blends back a little of the original so detail survives and
 * the effect is a wash, not a repaint.
 */
export function makeVariantColorize(tint: number): ColorMatrixFilter {
  const tr = ((tint >> 16) & 0xff) / 255;
  const tg = ((tint >> 8) & 0xff) / 255;
  const tb = (tint & 0xff) / 255;
  // Rec. 601 luma weights.
  const lr = 0.299;
  const lg = 0.587;
  const lb = 0.114;
  const filter = new ColorMatrixFilter();
  // Screen of a per-channel constant tint over the grayscale luminance.
  filter.matrix = [
    lr * (1 - tr), lg * (1 - tr), lb * (1 - tr), 0, tr,
    lr * (1 - tg), lg * (1 - tg), lb * (1 - tg), 0, tg,
    lr * (1 - tb), lg * (1 - tb), lb * (1 - tb), 0, tb,
    0, 0, 0, 1, 0,
  ];
  filter.alpha = VARIANT_TINT_STRENGTH;
  return filter;
}
