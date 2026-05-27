import { useEffect, useMemo, useRef, useState } from "react";
import type { AuctionListing, ItemSummary, UiFeedbackEntry } from "../../types";

type AuctionView = "browse" | "sell" | "mine";

interface Props {
  listings: AuctionListing[];
  inventory: ItemSummary[];
  playerName: string;
  feedbackFeed: UiFeedbackEntry[];
  onCommand: (cmd: string) => void;
}

export function AuctionPanel({ listings, inventory, playerName, feedbackFeed, onCommand }: Props) {
  const [view, setView] = useState<AuctionView>("browse");
  const [filter, setFilter] = useState("");
  const [selectedItemId, setSelectedItemId] = useState<string>("");
  const [priceDraft, setPriceDraft] = useState("");
  const [localMessage, setLocalMessage] = useState<string | null>(null);

  // Fetch the current listings when the panel opens. The panel is only mounted
  // while open (see App.tsx), so an effect guarded by a ref fires exactly once
  // per open — otherwise the panel would show stale/empty data until the user
  // hit Refresh. Mirrors the shop panel's load-on-open behaviour.
  const fetched = useRef(false);
  useEffect(() => {
    if (!fetched.current) {
      fetched.current = true;
      onCommand("auction list");
    }
  }, [onCommand]);

  const filtered = useMemo(() => {
    const lowered = filter.trim().toLowerCase();
    if (!lowered) return listings;
    return listings.filter((listing) =>
      listing.itemName.toLowerCase().includes(lowered) ||
      listing.seller.toLowerCase().includes(lowered),
    );
  }, [filter, listings]);

  const mine = useMemo(
    () => listings.filter((listing) => listing.seller.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0),
    [listings, playerName],
  );

  const sortedInventory = useMemo(
    () => [...inventory].sort((a, b) => a.name.localeCompare(b.name)),
    [inventory],
  );

  const selectedItem = sortedInventory.find((item) => item.id === selectedItemId) ?? null;
  const activeFeedback = useMemo(
    () => [...feedbackFeed].reverse().find((entry) => entry.scope === "auction") ?? null,
    [feedbackFeed],
  );

  const createListing = () => {
    if (!selectedItem) {
      setLocalMessage("Choose an inventory item to list.");
      return;
    }

    if (!/^\d+$/.test(priceDraft.trim())) {
      setLocalMessage("Enter a whole-number gold price.");
      return;
    }

    const price = Number(priceDraft);
    if (!Number.isSafeInteger(price) || price <= 0) {
      setLocalMessage("Listing price must be greater than zero.");
      return;
    }

    onCommand(`auction sell ${selectedItem.keyword} ${price}`);
    setLocalMessage(`Listing ${selectedItem.name} for ${price.toLocaleString()} gold.`);
    setPriceDraft("");
    setSelectedItemId("");
    setView("mine");
  };

  return (
    <div className="auction-panel">
      <div className="panel-header">
        <span className="panel-title">Auction House</span>
      </div>

      <div className="auction-view-tabs" role="tablist" aria-label="Auction views">
        {[
          { id: "browse", label: "Browse" },
          { id: "sell", label: "Create Listing" },
          { id: "mine", label: "My Listings" },
        ].map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            aria-selected={view === tab.id}
            className={`auction-view-tab ${view === tab.id ? "auction-view-tab-active" : ""}`}
            onClick={() => setView(tab.id as AuctionView)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className="auction-toolbar">
        <input
          type="text"
          className="auction-filter-input"
          placeholder={view === "browse" ? "Filter listings by item or seller..." : "Search current listings..."}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <button
          type="button"
          className="auction-refresh-btn"
          onClick={() => {
            onCommand(filter.trim().length > 0 ? `auction ${filter.trim()}` : "auction list");
            setLocalMessage("Refreshing auction listings.");
          }}
        >
          Refresh
        </button>
      </div>

      {localMessage && <p className="auction-local-message">{localMessage}</p>}
      {activeFeedback && (
        <p className={`auction-local-message auction-local-message-${activeFeedback.type}`}>
          {activeFeedback.message}
        </p>
      )}

      {view === "browse" && (
        filtered.length === 0 ? (
          <div className="auction-empty">
            <p>No auction listings match your filter.</p>
            <p>Try refreshing or create a listing from your inventory.</p>
          </div>
        ) : (
          <div className="auction-content">
            <table className="auction-table">
              <thead>
                <tr className="auction-header-row">
                  <th className="auction-col-item">Item</th>
                  <th className="auction-col-price">Price</th>
                  <th className="auction-col-seller">Seller</th>
                  <th className="auction-col-action" />
                </tr>
              </thead>
              <tbody>
                {filtered.map((listing) => {
                  const mineListing = listing.seller.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
                  return (
                    <tr key={listing.id} className={`auction-row${mineListing ? " auction-row-owned" : ""}`}>
                      <td className="auction-cell-item">
                        <div className="auction-item-main">
                          <span>{listing.itemName}</span>
                          {mineListing && <span className="auction-owned-badge">Yours</span>}
                        </div>
                      </td>
                      <td className="auction-cell-price">
                        <span className="auction-gold-coin" />
                        {listing.price.toLocaleString()}
                      </td>
                      <td className="auction-cell-seller">{mineListing ? "You" : listing.seller}</td>
                      <td className="auction-cell-action">
                        {mineListing ? (
                          <button
                            type="button"
                            className="auction-buy-btn auction-buy-btn-secondary"
                            onClick={() => {
                              onCommand(`auction cancel ${listing.id}`);
                              setLocalMessage(`Cancelling listing #${listing.id}.`);
                            }}
                          >
                            Cancel
                          </button>
                        ) : (
                          <button
                            type="button"
                            className="auction-buy-btn"
                            onClick={() => {
                              onCommand(`auction buy ${listing.id}`);
                              setLocalMessage(`Purchasing ${listing.itemName}.`);
                            }}
                          >
                            Buy
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )
      )}

      {view === "sell" && (
        <div className="auction-create">
          <section className="auction-create-card">
            <h3 className="auction-create-title">Create a Listing</h3>

            {sortedInventory.length === 0 ? (
              <div className="auction-empty">
                <p>You do not have any inventory items available to list right now.</p>
              </div>
            ) : (
              <>
                <div className="auction-create-field">
                  <span className="auction-create-label">Inventory Item</span>
                  <div className="auction-create-grid">
                    {sortedInventory.map((item) => (
                      <button
                        key={item.id}
                        type="button"
                        className={`auction-item-picker ${selectedItemId === item.id ? "auction-item-picker-active" : ""}`}
                        onClick={() => setSelectedItemId(item.id)}
                      >
                        <span className="auction-item-picker-name">{item.name}</span>
                        <span className="auction-item-picker-meta">
                          {item.slot ? `${item.slot} item` : "Inventory item"}
                        </span>
                      </button>
                    ))}
                  </div>
                </div>

                <label className="auction-create-field" htmlFor="auction-price-input">
                  <span className="auction-create-label">Sale Price</span>
                  <div className="auction-price-row">
                    <input
                      id="auction-price-input"
                      type="number"
                      min={1}
                      step={1}
                      inputMode="numeric"
                      className="auction-price-input"
                      placeholder="Enter price in gold"
                      value={priceDraft}
                      onChange={(event) => setPriceDraft(event.target.value)}
                    />
                    <button
                      type="button"
                      className="auction-create-submit"
                      onClick={createListing}
                    >
                      Post Listing
                    </button>
                  </div>
                </label>

                {selectedItem && (
                  <div className="auction-create-summary">
                    <span className="auction-create-summary-label">Ready to list</span>
                    <strong>{selectedItem.name}</strong>
                  </div>
                )}
              </>
            )}
          </section>
        </div>
      )}

      {view === "mine" && (
        mine.length === 0 ? (
          <div className="auction-empty">
            <p>You do not have any active listings.</p>
            <p>Open the create-listing view to post something from your inventory.</p>
          </div>
        ) : (
          <div className="auction-my-listings">
            {mine.map((listing) => (
              <article key={listing.id} className="auction-owned-card">
                <div className="auction-owned-copy">
                  <p className="auction-owned-item">{listing.itemName}</p>
                  <p className="auction-owned-price">{listing.price.toLocaleString()} gold</p>
                </div>
                <button
                  type="button"
                  className="auction-buy-btn auction-buy-btn-secondary"
                  onClick={() => {
                    onCommand(`auction cancel ${listing.id}`);
                    setLocalMessage(`Cancelling listing #${listing.id}.`);
                  }}
                >
                  Cancel Listing
                </button>
              </article>
            ))}
          </div>
        )
      )}
    </div>
  );
}
