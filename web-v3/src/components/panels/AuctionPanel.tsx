import { useState } from "react";
import type { AuctionListing } from "../../types";

interface Props {
  listings: AuctionListing[];
  onCommand: (cmd: string) => void;
}

export function AuctionPanel({ listings, onCommand }: Props) {
  const [filter, setFilter] = useState("");

  const filtered = filter
    ? listings.filter((l) => l.itemName.toLowerCase().includes(filter.toLowerCase()))
    : listings;

  return (
    <div className="auction-panel">
      <div className="panel-header">
        <span className="panel-title">Auction House</span>
      </div>

      <div className="auction-toolbar">
        <input
          type="text"
          className="auction-filter-input"
          placeholder="Filter by item name..."
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <button
          type="button"
          className="auction-refresh-btn"
          onClick={() => onCommand("auction list")}
        >
          Refresh
        </button>
      </div>

      {filtered.length === 0 ? (
        <div className="auction-empty">
          <p>No auction listings.</p>
          <p>Use the refresh button to load.</p>
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
              {filtered.map((listing) => (
                <tr key={listing.id} className="auction-row">
                  <td className="auction-cell-item">{listing.itemName}</td>
                  <td className="auction-cell-price">
                    <span className="auction-gold-coin" />
                    {listing.price.toLocaleString()}
                  </td>
                  <td className="auction-cell-seller">{listing.seller}</td>
                  <td className="auction-cell-action">
                    <button
                      type="button"
                      className="auction-buy-btn"
                      onClick={() => onCommand(`auction buy ${listing.id}`)}
                    >
                      Buy
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
