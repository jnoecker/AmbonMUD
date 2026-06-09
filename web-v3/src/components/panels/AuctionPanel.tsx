import { useEffect, useMemo, useRef, useState } from "react";
import type { AuctionListing, ItemSummary, UiFeedbackEntry } from "../../types";
import { resolveItemImage } from "../../imageDefaults";

interface Props {
  listings: AuctionListing[];
  inventory: ItemSummary[];
  playerName: string;
  /** Unclaimed demo characters can browse but not buy or sell. */
  isDemo: boolean;
  feedbackFeed: UiFeedbackEntry[];
  onCommand: (cmd: string) => void;
}

function gold(n: number): string {
  return n.toLocaleString();
}

/**
 * Auction House — reskinned onto the painted `auction_bg` frame. A single
 * unified board: live listings to buy on the left, your inventory + the
 * selected item's details + the set-price/list control on the upper right, and
 * your active listings along the bottom right. Fixed price, buy now, instant
 * delivery — no bidding.
 */
export function AuctionPanel({ listings, inventory, playerName, isDemo, feedbackFeed, onCommand }: Props) {
  const [filter, setFilter] = useState("");
  const [selectedItemId, setSelectedItemId] = useState<string>("");
  const [priceDraft, setPriceDraft] = useState("");
  const [localMessage, setLocalMessage] = useState<string | null>(null);

  // Fetch listings once per open (panel only mounts while open).
  const fetched = useRef(false);
  useEffect(() => {
    if (!fetched.current) {
      fetched.current = true;
      onCommand("auction list");
    }
  }, [onCommand]);

  const isMine = (seller: string) =>
    seller.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;

  const filtered = useMemo(() => {
    const lowered = filter.trim().toLowerCase();
    if (!lowered) return listings;
    return listings.filter(
      (l) => l.itemName.toLowerCase().includes(lowered) || l.seller.toLowerCase().includes(lowered),
    );
  }, [filter, listings]);

  const mine = useMemo(
    () => listings.filter((l) => l.seller.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0),
    [listings, playerName],
  );

  const sortedInventory = useMemo(
    () => [...inventory].sort((a, b) => a.name.localeCompare(b.name)),
    [inventory],
  );
  const selectedItem = sortedInventory.find((it) => it.id === selectedItemId) ?? null;

  const activeFeedback = useMemo(
    () => [...feedbackFeed].reverse().find((e) => e.scope === "auction") ?? null,
    [feedbackFeed],
  );
  const message = activeFeedback?.message ?? localMessage;
  const messageKind = activeFeedback?.type ?? "info";

  const listSelected = () => {
    if (isDemo) {
      setLocalMessage("Demo characters can't post listings. Claim your character first.");
      return;
    }
    if (!selectedItem) {
      setLocalMessage("Pick an inventory item to list.");
      return;
    }
    const price = Number(priceDraft);
    if (!/^\d+$/.test(priceDraft.trim()) || !Number.isSafeInteger(price) || price <= 0) {
      setLocalMessage("Enter a whole-number gold price above zero.");
      return;
    }
    onCommand(`auction sell ${selectedItem.keyword} ${price}`);
    setLocalMessage(`Listing ${selectedItem.name} for ${gold(price)} gold.`);
    setPriceDraft("");
    setSelectedItemId("");
  };

  const selectedImage = selectedItem ? resolveItemImage(selectedItem) : null;

  return (
    <div className="ah-board">
      {message && <p className={`ah-toast ah-toast-${messageKind}`}>{message}</p>}

      {/* ───────── Browse (left) ───────── */}
      <div className="ah-browse">
        <div className="ah-browse-head">
          <input
            type="text"
            className="ah-search"
            placeholder="Search items or sellers…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <button
            type="button"
            className="ah-refresh"
            onClick={() => {
              onCommand(filter.trim().length > 0 ? `auction ${filter.trim()}` : "auction list");
            }}
          >
            Refresh
          </button>
        </div>
        <div className="ah-browse-cols">
          <span className="ah-col-item">Item</span>
          <span className="ah-col-price">Price</span>
          <span className="ah-col-seller">Seller</span>
          <span className="ah-col-buy" />
        </div>
        <div className="ah-browse-list">
          {filtered.length === 0 ? (
            <p className="ah-empty">No listings match. Try refreshing or list something of your own.</p>
          ) : (
            filtered.map((l) => (
              <div key={l.id} className={`ah-row${isMine(l.seller) ? " ah-row-mine" : ""}`}>
                <span className="ah-col-item">{l.itemName}</span>
                <span className="ah-col-price"><span className="ah-coin" />{gold(l.price)}</span>
                <span className="ah-col-seller">{isMine(l.seller) ? "You" : l.seller}</span>
                <span className="ah-col-buy">
                  {isMine(l.seller) ? (
                    <button
                      type="button"
                      className="ah-btn ah-btn-cancel"
                      onClick={() => { onCommand(`auction cancel ${l.id}`); setLocalMessage(`Cancelling #${l.id}.`); }}
                    >
                      Cancel
                    </button>
                  ) : (
                    <button
                      type="button"
                      className="ah-btn ah-btn-buy"
                      disabled={isDemo}
                      title={isDemo ? "Claim your character to buy." : undefined}
                      onClick={() => { onCommand(`auction buy ${l.id}`); setLocalMessage(`Buying ${l.itemName}.`); }}
                    >
                      Buy Now
                    </button>
                  )}
                </span>
              </div>
            ))
          )}
        </div>
      </div>

      {/* ───────── Your inventory (mid) ───────── */}
      <div className="ah-inventory">
        <div className="ah-inv-list">
          {sortedInventory.length === 0 ? (
            <p className="ah-empty">No items to list.</p>
          ) : (
            sortedInventory.map((it) => {
              const img = resolveItemImage(it);
              return (
                <button
                  key={it.id}
                  type="button"
                  className={`ah-inv-row${selectedItemId === it.id ? " ah-inv-row-active" : ""}`}
                  onClick={() => setSelectedItemId(it.id)}
                >
                  <span className="ah-inv-icon">{img && <img src={img} alt="" />}</span>
                  <span className="ah-inv-name">{it.name}</span>
                </button>
              );
            })
          )}
        </div>
      </div>

      {/* ───────── Selected item (right) ───────── */}
      <div className="ah-selected">
        {selectedItem ? (
          <div className="ah-detail">
            <div className="ah-detail-icon">{selectedImage && <img src={selectedImage} alt="" />}</div>
            <div className="ah-detail-body">
              <h3 className="ah-detail-name">{selectedItem.name}</h3>
              <p className="ah-detail-sub">{selectedItem.itemType ?? selectedItem.slot ?? "Item"}</p>
              <dl className="ah-detail-stats">
                {selectedItem.damage ? (
                  <div><dt>Weapon Damage</dt><dd>{selectedItem.damage}</dd></div>
                ) : null}
                {selectedItem.armor ? (
                  <div><dt>Armor</dt><dd>+{selectedItem.armor}</dd></div>
                ) : null}
                {Object.entries(selectedItem.stats ?? {}).map(([k, v]) => (
                  <div key={k}><dt>{k}</dt><dd>{v > 0 ? `+${v}` : v}</dd></div>
                ))}
              </dl>
              {selectedItem.enchantments && selectedItem.enchantments.length > 0 && (
                <p className="ah-detail-ench">{selectedItem.enchantments.join(", ")}</p>
              )}
            </div>
          </div>
        ) : (
          <p className="ah-empty ah-detail-empty">Select an item from your inventory to list it.</p>
        )}
      </div>

      {/* ───────── List action (below selected) ───────── */}
      <div className="ah-list-action">
        <label className="ah-price-label" htmlFor="ah-price">Set Price</label>
        <div className="ah-price-row">
          <input
            id="ah-price"
            type="number"
            min={1}
            step={1}
            inputMode="numeric"
            className="ah-price-input"
            placeholder="Gold"
            value={priceDraft}
            onChange={(e) => setPriceDraft(e.target.value)}
          />
          <button
            type="button"
            className="ah-list-btn"
            disabled={isDemo || !selectedItem}
            title={isDemo ? "Claim your character to list items." : undefined}
            onClick={listSelected}
          >
            List Instantly
          </button>
        </div>
      </div>

      {/* ───────── My listings (bottom-right) ───────── */}
      <div className="ah-mine">
        <div className="ah-mine-list">
          {mine.length === 0 ? (
            <p className="ah-empty">You have no active listings.</p>
          ) : (
            mine.map((l) => (
              <div key={l.id} className="ah-mine-row">
                <span className="ah-mine-name">{l.itemName}</span>
                <span className="ah-mine-price"><span className="ah-coin" />{gold(l.price)}</span>
                <button
                  type="button"
                  className="ah-btn ah-btn-cancel"
                  onClick={() => { onCommand(`auction cancel ${l.id}`); setLocalMessage(`Cancelling #${l.id}.`); }}
                >
                  Cancel
                </button>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
