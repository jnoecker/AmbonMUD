import { useMemo, useState } from "react";
import { LOTTERY_SETTINGS } from "../../featureMetadata";
import type { LotteryInfo, UiFeedbackEntry } from "../../types";

interface LotteryPanelProps {
  lotteryInfo: LotteryInfo | null;
  uiFeedbackFeed: UiFeedbackEntry[];
  onCommand: (command: string) => void;
}

function formatCountdown(ms: number): string {
  if (ms <= 0) return "Drawing soon";
  const totalSeconds = Math.floor(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

export function LotteryPanel({ lotteryInfo, uiFeedbackFeed, onCommand }: LotteryPanelProps) {
  const [ticketDraft, setTicketDraft] = useState("1");

  const activeFeedback = useMemo(
    () => [...uiFeedbackFeed].reverse().find((e) => e.scope === "lottery") ?? null,
    [uiFeedbackFeed],
  );

  return (
    <div className="lottery-panel">
      <div className="panel-header">
        <span className="panel-title">Lottery</span>
      </div>

      {activeFeedback && (
        <p className={`systems-local-message systems-local-message-${activeFeedback.type}`}>
          {activeFeedback.message}
        </p>
      )}

      {lotteryInfo ? (
        <article className="systems-card">
          <div className="systems-card-header">
            <div>
              <p className="systems-card-label">Current Drawing</p>
              <h4>{lotteryInfo.jackpot.toLocaleString()} gold jackpot</h4>
            </div>
            <span className="systems-pill">{formatCountdown(lotteryInfo.nextDrawingMs)}</span>
          </div>
          <dl className="systems-stat-grid">
            <div><dt>Your Tickets</dt><dd>{lotteryInfo.playerTickets}</dd></div>
            <div><dt>Total Tickets</dt><dd>{lotteryInfo.totalTickets}</dd></div>
            <div><dt>Ticket Cost</dt><dd>{LOTTERY_SETTINGS.ticketCost} gold</dd></div>
            <div><dt>Per-Player Limit</dt><dd>{LOTTERY_SETTINGS.maxTicketsPerPlayer}</dd></div>
          </dl>
          <div className="systems-choice-list systems-choice-list-compact">
            {[1, 3, 5].map((count) => (
              <button
                key={count}
                type="button"
                className="systems-choice-card"
                onClick={() => onCommand(`lottery buy ${count}`)}
              >
                <span className="systems-choice-title">{count} ticket{count === 1 ? "" : "s"}</span>
                <span className="systems-choice-copy">{(count * LOTTERY_SETTINGS.ticketCost).toLocaleString()} gold</span>
              </button>
            ))}
          </div>
          <div className="systems-action-row">
            <input
              type="number"
              min={1}
              max={LOTTERY_SETTINGS.maxTicketsPerPlayer}
              step={1}
              inputMode="numeric"
              className="systems-number-input"
              value={ticketDraft}
              onChange={(event) => setTicketDraft(event.target.value)}
            />
            <button
              type="button"
              className="systems-primary-btn"
              onClick={() => {
                const count = Number(ticketDraft);
                if (!Number.isSafeInteger(count) || count < 1) return;
                onCommand(`lottery buy ${count}`);
              }}
            >
              Buy Custom Quantity
            </button>
            <button
              type="button"
              className="systems-secondary-btn"
              onClick={() => onCommand("lottery")}
            >
              Refresh Info
            </button>
          </div>
        </article>
      ) : (
        <p className="empty-note">Lottery information is not available right now.</p>
      )}
    </div>
  );
}
