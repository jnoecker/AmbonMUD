import { useMemo, useState } from "react";
import { useTickingClock } from "../../hooks/useTickingClock";
import type { LotteryInfo, UiFeedbackEntry } from "../../types";

interface LotteryPanelProps {
  lotteryInfo: LotteryInfo | null;
  uiFeedbackFeed: UiFeedbackEntry[];
  onCommand: (command: string) => void;
}

function pad(n: number): string { return n.toString().padStart(2, "0"); }

function formatHMS(ms: number): string {
  if (ms <= 0) return "00:00:00";
  const t = Math.floor(ms / 1000);
  return `${pad(Math.floor(t / 3600))}:${pad(Math.floor((t % 3600) / 60))}:${pad(t % 60)}`;
}

/**
 * Lottery — reskinned onto the painted `lottery_bg` frame. The labels (Current
 * Jackpot, Time Remaining, Owned Tickets, etc.) are painted; this overlays the
 * live values into their slots, plus the quantity selector, purchase summary,
 * and Buy Tickets button in the bottom panel.
 */
export function LotteryPanel({ lotteryInfo, uiFeedbackFeed, onCommand }: LotteryPanelProps) {
  const now = useTickingClock(1000);
  const [qty, setQty] = useState(1);

  const activeFeedback = useMemo(
    () => [...uiFeedbackFeed].reverse().find((e) => e.scope === "lottery") ?? null,
    [uiFeedbackFeed],
  );

  if (!lotteryInfo) {
    return (
      <div className="lottery-board lottery-board-empty">
        <p className="lot-empty">Fortune is quiet. Lottery information is not available right now.</p>
      </div>
    );
  }

  const { jackpot, totalTickets, playerTickets, nextDrawingMs, ticketCost, maxTicketsPerPlayer } = lotteryInfo;
  const cap = Math.min(10, maxTicketsPerPlayer || 10);
  const clamp = (n: number) => Math.max(1, Math.min(cap, n));
  const total = qty * ticketCost;

  return (
    <div className="lottery-board">
      <div className="lot-jackpot">
        <span className="lot-jackpot-value">{jackpot.toLocaleString()}</span>
        <span className="lot-jackpot-unit">Gold Pieces</span>
      </div>

      <div className="lot-countdown">{formatHMS(nextDrawingMs - now)}</div>
      <div className="lot-owned">{playerTickets}</div>
      <div className="lot-total">{totalTickets.toLocaleString()}</div>
      <div className="lot-cost">{ticketCost.toLocaleString()}</div>
      <div className="lot-limit">{maxTicketsPerPlayer}<span className="lot-limit-unit">Tickets Max</span></div>

      <div className="lot-buy">
        <div className="lot-buy-sub">Select Quantity (1–{cap})</div>
        <div className="lot-qty" role="group" aria-label="Ticket quantity">
          <button type="button" className="lot-qty-step" onClick={() => setQty((q) => clamp(q - 10))} aria-label="Minus ten">−10</button>
          <button type="button" className="lot-qty-step" onClick={() => setQty((q) => clamp(q - 1))} aria-label="Minus one">−</button>
          {Array.from({ length: cap }, (_, i) => i + 1).map((n) => (
            <button key={n} type="button" className={`lot-qty-num${qty === n ? " is-active" : ""}`} aria-pressed={qty === n} onClick={() => setQty(n)}>{n}</button>
          ))}
          <button type="button" className="lot-qty-step" onClick={() => setQty((q) => clamp(q + 1))} aria-label="Plus one">+</button>
          <button type="button" className="lot-qty-step" onClick={() => setQty((q) => clamp(q + 10))} aria-label="Plus ten">+10</button>
        </div>

        <p className="lot-summary">
          You will purchase <strong>{qty}</strong> ticket{qty === 1 ? "" : "s"} for <strong>{total.toLocaleString()}</strong> gold pieces.
        </p>

        {activeFeedback && (
          <p className={`lot-feedback lot-feedback-${activeFeedback.type}`}>{activeFeedback.message}</p>
        )}

        <button type="button" className="lot-buy-btn" onClick={() => onCommand(`lottery buy ${qty}`)}>Buy Tickets</button>
      </div>
    </div>
  );
}
