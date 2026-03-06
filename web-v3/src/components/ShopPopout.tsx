import { useState } from "react";
import type { ItemSummary, ShopState } from "../types";

interface ShopPopoutProps {
  shop: ShopState;
  inventory: ItemSummary[];
  gold: number;
  onBuyItem: (keyword: string) => void;
  onSellItem: (keyword: string) => void;
}

export function ShopPopout({ shop, inventory, gold, onBuyItem, onSellItem }: ShopPopoutProps) {
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
              return (
                <div
                  key={item.id}
                  className={`shop-popout-card${canAfford ? "" : " shop-popout-card-unaffordable"}`}
                >
                  <div className="shop-popout-card-top">
                    <div className="shop-popout-card-info">
                      {item.image && <img src={item.image} alt="" className="shop-popout-thumb" />}
                      <div className="shop-popout-card-text">
                        <span className="shop-popout-card-name">{item.name}</span>
                        {item.description && (
                          <span className="shop-popout-card-desc">{item.description}</span>
                        )}
                        <span className="shop-popout-card-stats">
                          {[
                            item.slot,
                            item.damage > 0 && `${item.damage} dmg`,
                            item.armor > 0 && `${item.armor} arm`,
                            item.consumable && "consumable",
                          ].filter(Boolean).join(" \u00b7 ") || "misc"}
                        </span>
                      </div>
                    </div>
                    <div className="shop-popout-card-action">
                      <span className={`shop-popout-card-price${canAfford ? "" : " shop-popout-card-price-cant"}`}>
                        {item.buyPrice.toLocaleString()}g
                      </span>
                      <button
                        type="button"
                        className="soft-button shop-popout-buy-btn"
                        disabled={!canAfford}
                        onClick={() => onBuyItem(item.keyword)}
                      >
                        Buy
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
                <div key={`${item.id}-${index}`} className="shop-popout-card">
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
                        <span className="shop-popout-card-price-cant">Worthless</span>
                      )}
                      <button
                        type="button"
                        className="soft-button shop-popout-sell-btn"
                        disabled={sellPrice <= 0}
                        onClick={() => onSellItem(item.keyword)}
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
