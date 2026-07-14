import { useState } from "react";
import type { ItemSummary, ShopItem, ShopState } from "../types";

interface ShopPopoutProps {
  shop: ShopState;
  inventory: ItemSummary[];
  equipment: Record<string, ItemSummary>;
  gold: number;
  onBuyItem: (keyword: string) => void;
  onSellItem: (keyword: string) => void;
  /** Open the item detail card for a shop stock item (has full description). */
  onShowBuyItem: (item: ShopItem) => void;
  /** Open the item detail card for an owned item (looks up its full detail). */
  onShowSellItem: (item: ItemSummary, image: string | null) => void;
}

/**
 * Format a signed delta for display, e.g. +2 / -1 / 0.
 */
function formatDelta(delta: number): string {
  if (delta > 0) return `+${delta}`;
  if (delta < 0) return `${delta}`;
  return "0";
}

/**
 * Render a single stat chip ("Damage 4") with an optional delta vs. the currently
 * equipped item in the same slot. Delta omitted when no item equipped or delta is 0.
 */
function StatChip({
  label,
  value,
  delta,
}: {
  label: string;
  value: number;
  delta: number | null;
}) {
  const deltaClass = delta === null ? "" : delta > 0 ? " shop-stat-delta-up" : delta < 0 ? " shop-stat-delta-down" : " shop-stat-delta-same";
  const title = `${label}: ${value}${delta !== null ? ` (${formatDelta(delta)} vs equipped)` : ""}`;
  return (
    <span className="shop-stat-chip" title={title} aria-label={title}>
      <span className="shop-stat-chip-label">{label}</span>
      <span className="shop-stat-chip-value">{value}</span>
      {delta !== null && (
        <span className={`shop-stat-delta${deltaClass}`}>{formatDelta(delta)}</span>
      )}
    </span>
  );
}

/**
 * Build the comparison stat chips for a shop item, using the player's currently
 * equipped item in the same slot (if any) as the baseline.
 */
function shopItemChips(item: ShopItem, equipment: Record<string, ItemSummary>) {
  const equipped: ItemSummary | undefined = item.slot ? equipment[item.slot] : undefined;
  const chips: Array<{ label: string; value: number; delta: number | null }> = [];

  if (item.damage > 0) {
    const equippedDamage = equipped?.damage ?? 0;
    chips.push({
      label: "Damage",
      value: item.damage,
      delta: equipped ? item.damage - equippedDamage : null,
    });
  }
  if (item.armor > 0) {
    const equippedArmor = equipped?.armor ?? 0;
    chips.push({
      label: "Armor",
      value: item.armor,
      delta: equipped ? item.armor - equippedArmor : null,
    });
  }
  return chips;
}

export function ShopPopout({ shop, inventory, equipment, gold, onBuyItem, onSellItem, onShowBuyItem, onShowSellItem }: ShopPopoutProps) {
  const [tab, setTab] = useState<"buy" | "sell">("buy");

  return (
    <div className="shop-popout">
      <div className="shop-popout-wallet">
        <span className="shop-popout-gold-icon" aria-hidden="true" />
        <span className="shop-popout-gold-amount">{gold.toLocaleString()}</span>
        <span className="shop-popout-gold-label">gold</span>
      </div>

      <div className="shop-popout-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          className={`shop-popout-tab${tab === "buy" ? " shop-popout-tab-active" : ""}`}
          aria-selected={tab === "buy"}
          onClick={() => setTab("buy")}
        >
          Buy ({shop.items.length})
        </button>
        <button
          type="button"
          role="tab"
          className={`shop-popout-tab${tab === "sell" ? " shop-popout-tab-active" : ""}`}
          aria-selected={tab === "sell"}
          onClick={() => setTab("sell")}
        >
          Sell ({inventory.length})
        </button>
      </div>

      {tab === "buy" ? (
        <div className="shop-popout-list" role="tabpanel">
          {shop.items.length === 0 ? (
            <p className="empty-note">This shop has no items for sale.</p>
          ) : (
            shop.items.map((item) => {
              const canAfford = gold >= item.buyPrice;
              const chips = shopItemChips(item, equipment);
              const slotLabel = item.slot ?? (item.consumable ? "consumable" : "misc");
              return (
                <div
                  key={item.id}
                  className={`shop-popout-card${canAfford ? "" : " shop-popout-card-unaffordable"}`}
                  role="button"
                  tabIndex={0}
                  onClick={() => onShowBuyItem(item)}
                  onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onShowBuyItem(item); } }}
                >
                  <div className="shop-popout-card-top">
                    <div className="shop-popout-card-info">
                      {item.image && <img src={item.image} alt="" className="shop-popout-thumb" />}
                      <div className="shop-popout-card-text">
                        <span className="shop-popout-card-name">{item.name}</span>
                        {item.description && (
                          <span className="shop-popout-card-desc">{item.description}</span>
                        )}
                        <div className="shop-popout-card-stats">
                          <span className="shop-stat-slot" title={item.slot ? `Equipment slot: ${item.slot}` : undefined}>{slotLabel}</span>
                          {chips.map((chip) => (
                            <StatChip
                              key={chip.label}
                              label={chip.label}
                              value={chip.value}
                              delta={chip.delta}
                            />
                          ))}
                          {item.consumable && item.slot && (
                            <span className="shop-stat-slot">consumable</span>
                          )}
                          {item.mountSpeed != null && (
                            <span className="shop-stat-slot" title="Ride speed over the base travel pace">
                              🐎 {item.mountSpeed}× speed
                            </span>
                          )}
                          {item.mountFlying === true && (
                            <span
                              className="shop-stat-slot"
                              title="Can fly — travel to explored rooms in any realm from the world map"
                            >
                              🦅 flying
                            </span>
                          )}
                          {item.owned && <span className="shop-stat-slot">owned</span>}
                        </div>
                      </div>
                    </div>
                    <div className="shop-popout-card-action">
                      <span className={`shop-popout-card-price${canAfford ? "" : " shop-popout-card-price-cant"}`}>
                        {item.buyPrice.toLocaleString()}g
                      </span>
                      <button
                        type="button"
                        className="soft-button shop-popout-buy-btn"
                        disabled={!canAfford || item.owned}
                        onClick={(e) => { e.stopPropagation(); onBuyItem(item.keyword); }}
                      >
                        {item.owned ? "Owned" : "Buy"}
                      </button>
                    </div>
                  </div>
                </div>
              );
            })
          )}
        </div>
      ) : (
        <div className="shop-popout-list" role="tabpanel">
          {inventory.length === 0 ? (
            <p className="empty-note">Your inventory is empty.</p>
          ) : (
            inventory.map((item, index) => {
              const sellPrice = Math.round((item.basePrice ?? 0) * shop.sellMultiplier);
              return (
                <div
                  key={`${item.id}-${index}`}
                  className="shop-popout-card"
                  role="button"
                  tabIndex={0}
                  onClick={() => onShowSellItem(item, item.image ?? null)}
                  onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onShowSellItem(item, item.image ?? null); } }}
                >
                  <div className="shop-popout-card-top">
                    <div className="shop-popout-card-info">
                      {item.image && <img src={item.image} alt="" className="shop-popout-thumb" />}
                      <div className="shop-popout-card-text">
                        <span className="shop-popout-card-name">{item.name}</span>
                        <span className="shop-popout-card-stats">
                          {item.slot ?? "misc"}
                        </span>
                      </div>
                    </div>
                    <div className="shop-popout-card-action">
                      {sellPrice > 0 ? (
                        <span className="shop-popout-card-price">{sellPrice.toLocaleString()}g</span>
                      ) : (
                        <span className="shop-popout-card-price-cant">Cannot sell</span>
                      )}
                      <button
                        type="button"
                        className="soft-button shop-popout-sell-btn"
                        disabled={sellPrice <= 0}
                        onClick={(e) => { e.stopPropagation(); onSellItem(item.keyword); }}
                      >
                        Sell
                      </button>
                    </div>
                  </div>
                </div>
              );
            })
          )}
        </div>
      )}
    </div>
  );
}
