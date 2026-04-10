import { gameStateRef } from "./canvas/GameStateBridge";

/**
 * Resolves the display image for an item, falling back to a slot-based or
 * generic default from Server.Assets when no custom image is set.
 */
export function resolveItemImage(item: { image?: string | null; slot?: string | null }): string | null {
  if (item.image) return item.image;
  const assets = gameStateRef.current.serverAssets;
  if (item.slot) {
    const slotKey = `default_item_${item.slot}`;
    if (assets[slotKey]) return assets[slotKey];
  }
  return assets["default_item_generic"] ?? null;
}
