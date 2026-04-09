import { useMemo, useState } from "react";
import type { ItemSummary, TradeState } from "../types";

interface TradePanelProps {
  trade: TradeState;
  inventory: ItemSummary[];
  playerGold: number;
  onCommand: (cmd: string) => void;
}

function statusCopy(accepted: boolean, itemCount: number, gold: number): string {
  if (accepted) return "Accepted";
  if (itemCount === 0 && gold === 0) return "Waiting for an offer";
  return "Pending review";
}

export function TradePanel({ trade, inventory, playerGold, onCommand }: TradePanelProps) {
  const [goldDraft, setGoldDraft] = useState<string | null>(null);
  const [localMessage, setLocalMessage] = useState<string | null>(null);

  const sortedInventory = useMemo(
    () => [...inventory].sort((a, b) => a.name.localeCompare(b.name)),
    [inventory],
  );

  const goldDraftValue = goldDraft ?? (trade.myGold > 0 ? String(trade.myGold) : "");

  const applyGoldOffer = () => {
    const trimmed = goldDraftValue.trim();
    if (trimmed.length === 0) {
      onCommand("trade offer 0 gold");
      setGoldDraft("");
      setLocalMessage("Gold offer cleared.");
      return;
    }

    if (!/^\d+$/.test(trimmed)) {
      setLocalMessage("Enter a whole-number gold amount.");
      return;
    }

    const amount = Number(trimmed);
    if (!Number.isSafeInteger(amount) || amount < 0) {
      setLocalMessage("Gold offer must be a valid non-negative amount.");
      return;
    }
    if (amount > playerGold) {
      setLocalMessage(`You only have ${playerGold.toLocaleString()} gold available.`);
      return;
    }

    onCommand(`trade offer ${amount} gold`);
    setGoldDraft(String(amount));
    setLocalMessage(amount === 0 ? "Gold offer cleared." : `Offering ${amount.toLocaleString()} gold.`);
  };

  const acceptanceWillReset = trade.myAccepted || trade.theirAccepted;

  return (
    <div className="trade-panel" role="dialog" aria-label="Trade">
      <div className="trade-header">
        <div>
          <h3 className="trade-title">Trading with {trade.partner}</h3>
          <p className="trade-subtitle">
            Review both offers, adjust items or gold, then accept when everything looks right.
          </p>
        </div>
        <button
          type="button"
          className="trade-cancel-btn"
          onClick={() => onCommand("trade cancel")}
        >
          Cancel
        </button>
      </div>

      <div className="trade-columns">
        <div className="trade-column trade-column-manage">
          <div className="trade-column-topline">
            <h4 className="trade-column-label">Your Offer</h4>
            <span className={`trade-status ${trade.myAccepted ? "trade-accepted" : ""}`}>
              {statusCopy(trade.myAccepted, trade.myItems.length, trade.myGold)}
            </span>
          </div>

          <div className="trade-items">
            {trade.myItems.map((item, i) => (
              <div key={`my-${item.id}-${i}`} className="trade-item trade-item-owned">
                <span>{item.name}</span>
                <button
                  type="button"
                  className="trade-item-remove"
                  onClick={() => {
                    onCommand(`trade remove ${item.id}`);
                    setLocalMessage(`Removing ${item.name} from your offer.`);
                  }}
                >
                  Remove
                </button>
              </div>
            ))}
            {trade.myGold > 0 && (
              <div className="trade-item trade-gold">{trade.myGold.toLocaleString()} gold</div>
            )}
            {trade.myItems.length === 0 && trade.myGold === 0 && (
              <div className="trade-item trade-empty">Nothing offered yet.</div>
            )}
          </div>

          <div className="trade-offer-controls">
            <div className="trade-gold-editor">
              <label className="trade-field-label" htmlFor="trade-gold-input">Gold Offer</label>
              <div className="trade-gold-row">
                <input
                  id="trade-gold-input"
                  type="number"
                  min={0}
                  step={1}
                  inputMode="numeric"
                  className="trade-gold-input"
                  placeholder="0"
                  value={goldDraftValue}
                  onChange={(event) => setGoldDraft(event.target.value)}
                />
                <button
                  type="button"
                  className="trade-secondary-btn"
                  onClick={applyGoldOffer}
                >
                  Set Gold
                </button>
                <button
                  type="button"
                  className="trade-secondary-btn"
                  onClick={() => {
                    setGoldDraft("");
                    onCommand("trade offer 0 gold");
                    setLocalMessage("Gold offer cleared.");
                  }}
                >
                  Clear
                </button>
              </div>
              <p className="trade-available-gold">Available: {playerGold.toLocaleString()} gold</p>
            </div>

            <div className="trade-inventory-picker">
              <div className="trade-column-topline">
                <h5 className="trade-picker-title">Add Inventory Items</h5>
                <span className="trade-picker-count">{sortedInventory.length} available</span>
              </div>
              {sortedInventory.length === 0 ? (
                <p className="trade-picker-empty">You have no inventory items left to offer.</p>
              ) : (
                <div className="trade-picker-list">
                  {sortedInventory.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      className="trade-picker-item"
                      onClick={() => {
                        onCommand(`trade offer ${item.keyword}`);
                        setLocalMessage(`Adding ${item.name} to your offer.`);
                      }}
                    >
                      <span className="trade-picker-name">{item.name}</span>
                      {item.slot && <span className="trade-picker-meta">{item.slot}</span>}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        <div className="trade-divider" />

        <div className="trade-column">
          <div className="trade-column-topline">
            <h4 className="trade-column-label">{trade.partner}&apos;s Offer</h4>
            <span className={`trade-status ${trade.theirAccepted ? "trade-accepted" : ""}`}>
              {statusCopy(trade.theirAccepted, trade.theirItems.length, trade.theirGold)}
            </span>
          </div>
          <div className="trade-items">
            {trade.theirItems.map((item, i) => (
              <div key={`their-${item.id}-${i}`} className="trade-item">{item.name}</div>
            ))}
            {trade.theirGold > 0 && (
              <div className="trade-item trade-gold">{trade.theirGold.toLocaleString()} gold</div>
            )}
            {trade.theirItems.length === 0 && trade.theirGold === 0 && (
              <div className="trade-item trade-empty">Nothing offered yet.</div>
            )}
          </div>
        </div>
      </div>

      <div className="trade-actions">
        <div className="trade-action-copy">
          {acceptanceWillReset ? (
            <p className="trade-reset-note">Any offer change resets both players back to pending review.</p>
          ) : (
            <p className="trade-reset-note">Offer updates appear live for both players.</p>
          )}
          {localMessage && <p className="trade-local-message">{localMessage}</p>}
        </div>
        <div className="trade-action-buttons">
          <button
            type="button"
            className="trade-secondary-btn"
            onClick={() => onCommand("trade status")}
          >
            Refresh
          </button>
          <button
            type="button"
            className="trade-accept-btn"
            onClick={() => onCommand("trade accept")}
            disabled={trade.myAccepted}
          >
            {trade.myAccepted ? "Waiting..." : "Accept Trade"}
          </button>
        </div>
      </div>
    </div>
  );
}
