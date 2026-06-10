import { useEffect, useState } from "react";
import type { BankState } from "../../types";

interface BankPanelProps {
  bankState: BankState | null;
  serverAssets: Record<string, string>;
  onCommand: (cmd: string) => void;
}

export function BankPanel({ bankState, serverAssets, onCommand }: BankPanelProps) {
  const [goldAmount, setGoldAmount] = useState("");
  const [itemKeyword, setItemKeyword] = useState("");
  const emblem = serverAssets["bank_vault"] ?? null;

  // Coin glint whenever the deposited gold goes up.
  const gold = bankState?.gold ?? 0;
  const [seenGold, setSeenGold] = useState(gold);
  const [glint, setGlint] = useState(false);
  if (gold !== seenGold) {
    const increased = gold > seenGold;
    setSeenGold(gold);
    if (increased) setGlint(true);
  }
  useEffect(() => {
    if (!glint) return;
    const timer = setTimeout(() => setGlint(false), 900);
    return () => clearTimeout(timer);
  }, [glint]);

  if (!bankState) {
    return (
      <div className="bank-panel bank-vault">
        <div className="bank-empty-state">
          {emblem ? (
            <img className="bank-empty-emblem" src={emblem} alt="" />
          ) : (
            <span className="bank-empty-icon">{"\u{1F3E6}"}</span>
          )}
          <p className="empty-note">The vault is quiet.</p>
          <p className="bank-hint">
            Visit a bank room and use the <code>bank balance</code> command.
          </p>
        </div>
      </div>
    );
  }

  const capacityPct = bankState.maxItems > 0
    ? Math.round((bankState.items.length / bankState.maxItems) * 100)
    : 0;

  return (
    <div className="bank-panel bank-vault">
      <div className="bank-content">
        {/* Gold balance */}
        <div className="bank-gold-section">
          <div className="bank-gold-heading">
            {emblem && <img className="bank-emblem" src={emblem} alt="" />}
            <span className="bank-gold-label">Gold on Deposit</span>
          </div>
          <div className={`bank-gold-value${glint ? " is-glint" : ""}`}>{gold.toLocaleString()}</div>
        </div>

        {/* Gold deposit/withdraw */}
        <div className="bank-gold-actions">
          <input
            type="number"
            className="bank-gold-input"
            placeholder="Amount"
            aria-label="Gold amount"
            min={1}
            value={goldAmount}
            onChange={(e) => setGoldAmount(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                const amt = goldAmount.trim();
                if (amt) {
                  onCommand(`bank deposit ${amt}`);
                  setGoldAmount("");
                }
              }
            }}
          />
          <button
            type="button"
            className="bank-action-btn"
            disabled={!goldAmount.trim()}
            onClick={() => { onCommand(`bank deposit ${goldAmount.trim()}`); setGoldAmount(""); }}
          >
            Deposit
          </button>
          <button
            type="button"
            className="bank-action-btn"
            disabled={!goldAmount.trim()}
            onClick={() => { onCommand(`bank withdraw ${goldAmount.trim()}`); setGoldAmount(""); }}
          >
            Withdraw
          </button>
        </div>

        {/* Capacity indicator */}
        <div className="bank-capacity-section">
          <div className="bank-capacity-header">
            <span className="bank-capacity-label">Item Vault</span>
            <span className="bank-capacity-count">
              {bankState.items.length} / {bankState.maxItems}
            </span>
          </div>
          <div
            className="bank-capacity-bar-track"
            role="progressbar"
            aria-valuenow={capacityPct}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label="Bank vault capacity"
          >
            <div
              className={`bank-capacity-bar-fill${capacityPct >= 90 ? " bank-capacity-full" : ""}`}
              style={{ width: `${capacityPct}%` }}
            />
          </div>
        </div>

        {/* Item deposit input */}
        <div className="bank-item-actions">
          <input
            type="text"
            className="bank-item-input"
            placeholder="Item keyword"
            aria-label="Item keyword"
            value={itemKeyword}
            onChange={(e) => setItemKeyword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                const kw = itemKeyword.trim();
                if (kw) {
                  onCommand(`bank deposit ${kw}`);
                  setItemKeyword("");
                }
              }
            }}
          />
          <button
            type="button"
            className="bank-action-btn"
            disabled={!itemKeyword.trim()}
            onClick={() => { onCommand(`bank deposit ${itemKeyword.trim()}`); setItemKeyword(""); }}
          >
            Store
          </button>
        </div>

        {/* Item list */}
        {bankState.items.length === 0 ? (
          <p className="bank-items-empty">Your vault is empty.</p>
        ) : (
          <ul className="bank-item-list">
            {bankState.items.map((item) => (
              <li key={item.id} className="bank-item-row">
                <div className="bank-item-info">
                  {item.image && (
                    <img
                      src={item.image}
                      alt=""
                      className="bank-item-image"
                      loading="lazy"
                    />
                  )}
                  <span className="bank-item-name">{item.name}</span>
                </div>
                <button
                  type="button"
                  className="bank-withdraw-btn"
                  onClick={() => onCommand(`bank withdraw ${item.keyword}`)}
                >
                  Withdraw
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
