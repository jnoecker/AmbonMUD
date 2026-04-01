import type { TradeState } from "../types";

interface TradePanelProps {
  trade: TradeState;
  onCommand: (cmd: string) => void;
}

export function TradePanel({ trade, onCommand }: TradePanelProps) {
  return (
    <div className="trade-panel" role="dialog" aria-label="Trade">
      <div className="trade-header">
        <h3 className="trade-title">Trading with {trade.partner}</h3>
        <button
          type="button"
          className="trade-cancel-btn"
          onClick={() => onCommand("trade cancel")}
        >
          Cancel
        </button>
      </div>

      <div className="trade-columns">
        <div className="trade-column">
          <h4 className="trade-column-label">Your Offer</h4>
          <div className="trade-items">
            {trade.myItems.map((item, i) => (
              <div key={`my-${item.id}-${i}`} className="trade-item">{item.name}</div>
            ))}
            {trade.myGold > 0 && (
              <div className="trade-item trade-gold">{trade.myGold} gold</div>
            )}
            {trade.myItems.length === 0 && trade.myGold === 0 && (
              <div className="trade-item trade-empty">Nothing offered</div>
            )}
          </div>
          <div className={`trade-status ${trade.myAccepted ? "trade-accepted" : ""}`}>
            {trade.myAccepted ? "Accepted" : "Pending"}
          </div>
        </div>

        <div className="trade-divider" />

        <div className="trade-column">
          <h4 className="trade-column-label">{trade.partner}&apos;s Offer</h4>
          <div className="trade-items">
            {trade.theirItems.map((item, i) => (
              <div key={`their-${item.id}-${i}`} className="trade-item">{item.name}</div>
            ))}
            {trade.theirGold > 0 && (
              <div className="trade-item trade-gold">{trade.theirGold} gold</div>
            )}
            {trade.theirItems.length === 0 && trade.theirGold === 0 && (
              <div className="trade-item trade-empty">Nothing offered</div>
            )}
          </div>
          <div className={`trade-status ${trade.theirAccepted ? "trade-accepted" : ""}`}>
            {trade.theirAccepted ? "Accepted" : "Pending"}
          </div>
        </div>
      </div>

      <div className="trade-actions">
        <button
          type="button"
          className="trade-accept-btn"
          onClick={() => onCommand("trade accept")}
          disabled={trade.myAccepted}
        >
          {trade.myAccepted ? "Waiting..." : "Accept"}
        </button>
      </div>

      <p className="trade-hint">
        Use <code>trade offer &lt;item&gt;</code> or <code>trade offer &lt;amount&gt; gold</code> to add to your offer.
      </p>
    </div>
  );
}
