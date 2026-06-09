import { useEffect, useRef, useState } from "react";
import type { ItemSummary, MailEntry, MailMessage } from "../../types";

interface MailPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  inbox: MailEntry[] | null;
  openMessage: MailMessage | null;
  /** The player's carried items, for the attach-item picker. */
  inventory: ItemSummary[];
  /** The player's gold, to cap the attach-gold field. */
  gold: number;
  onReadMessage: (index: number) => void;
  onDeleteMessage: (index: number) => void;
  onCompose: (recipient: string, body: string, gold: number, itemKeyword: string | null) => void;
  onClaim: (index: number) => void;
  onClearMessage: () => void;
  onCommand: (cmd: string) => void;
}

function hasUnclaimed(m: { gold: number; itemName: string | null; claimed: boolean }): boolean {
  return !m.claimed && (m.gold > 0 || m.itemName !== null);
}

function formatDate(epochMs: number): string {
  if (!epochMs) return "";
  const d = new Date(epochMs);
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

export function MailPanel({
  connected,
  hasCharacterProfile,
  inbox,
  openMessage,
  inventory,
  gold,
  onReadMessage,
  onDeleteMessage,
  onCompose,
  onClaim,
  onClearMessage,
  onCommand,
}: MailPanelProps) {
  const [composeTarget, setComposeTarget] = useState("");
  const [composeBody, setComposeBody] = useState("");
  const [composeGold, setComposeGold] = useState("");
  const [composeItem, setComposeItem] = useState("");
  const [showCompose, setShowCompose] = useState(false);
  const [confirmDeleteIndex, setConfirmDeleteIndex] = useState<number | null>(null);
  const autoLoaded = useRef(false);

  useEffect(() => {
    if (connected && hasCharacterProfile && inbox === null && !autoLoaded.current) {
      autoLoaded.current = true;
      onCommand("mail");
    }
  }, [connected, hasCharacterProfile, inbox, onCommand]);

  if (!connected) {
    return <p className="empty-note">Connect to view mail.</p>;
  }
  if (!hasCharacterProfile) {
    return <p className="empty-note">Log in to view mail.</p>;
  }

  if (inbox === null) {
    return (
      <div className="mail-panel" aria-label="Mail">
        <div className="mail-empty-state">
          <span className="mail-empty-icon" aria-hidden="true">{"\u2709"}</span>
          <p className="empty-note">Checking your mailbox&hellip;</p>
        </div>
      </div>
    );
  }

  const unreadCount = inbox.filter((m) => !m.read).length;

  // Reading a message
  if (openMessage) {
    return (
      <div className="mail-panel" aria-label="Mail">
        <div className="mail-message-view" aria-live="polite">
          <div className="mail-message-header">
            <button className="mail-back-button" aria-label="Back to inbox" onClick={onClearMessage}>
              &larr; Back
            </button>
            <span className="mail-message-title">{openMessage.from}</span>
            <span className="mail-message-date">{formatDate(openMessage.date)}</span>
          </div>
          <pre className="mail-message-body">{openMessage.body}</pre>
          {(openMessage.gold > 0 || openMessage.itemName) && (
            <div className="mail-attachments">
              <span className="mail-attach-label">Attached</span>
              <div className="mail-attach-items">
                {openMessage.gold > 0 && (
                  <span className="mail-attach-chip mail-attach-gold">{openMessage.gold.toLocaleString()} gold</span>
                )}
                {openMessage.itemName && (
                  <span className="mail-attach-chip mail-attach-item">{openMessage.itemName}</span>
                )}
              </div>
              {openMessage.claimed ? (
                <span className="mail-attach-claimed">{"✓"} Claimed</span>
              ) : (
                <button className="mail-claim-button" onClick={() => onClaim(openMessage.index)}>
                  Claim
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    );
  }

  // Compose form
  if (showCompose) {
    const goldValue = Math.max(0, Math.min(gold, Math.floor(Number(composeGold) || 0)));
    const canSend = composeTarget.trim().length > 0 && composeBody.trim().length > 0 && goldValue <= gold;

    const resetCompose = () => {
      setShowCompose(false);
      setComposeTarget("");
      setComposeBody("");
      setComposeGold("");
      setComposeItem("");
    };

    const handleSend = () => {
      if (!canSend) return;
      onCompose(composeTarget.trim(), composeBody, goldValue, composeItem || null);
      resetCompose();
    };

    return (
      <div className="mail-panel" aria-label="Mail">
        <div className="mail-compose">
          <div className="mail-message-header">
            <button className="mail-back-button" aria-label="Back to inbox" onClick={resetCompose}>
              &larr; Back
            </button>
            <span className="mail-message-title">New Message</span>
          </div>
          <div className="mail-compose-form">
            <label className="mail-compose-label" htmlFor="mail-compose-recipient">To</label>
            <input
              id="mail-compose-recipient"
              type="text"
              className="mail-compose-input"
              placeholder="Recipient name"
              value={composeTarget}
              onChange={(e) => setComposeTarget(e.target.value)}
              autoFocus
            />
            <label className="mail-compose-label" htmlFor="mail-compose-body">Message</label>
            <textarea
              id="mail-compose-body"
              className="mail-compose-textarea"
              placeholder="Write your message..."
              rows={6}
              value={composeBody}
              onChange={(e) => setComposeBody(e.target.value)}
            />
            <div className="mail-compose-attach">
              <div className="mail-compose-attach-field">
                <label className="mail-compose-label" htmlFor="mail-compose-gold">Attach gold</label>
                <input
                  id="mail-compose-gold"
                  type="number"
                  min={0}
                  max={gold}
                  step={1}
                  inputMode="numeric"
                  className="mail-compose-input"
                  placeholder="0"
                  value={composeGold}
                  onChange={(e) => setComposeGold(e.target.value)}
                />
                <span className="mail-compose-hint">You have {gold.toLocaleString()} gold</span>
              </div>
              <div className="mail-compose-attach-field">
                <label className="mail-compose-label" htmlFor="mail-compose-item">Attach item</label>
                <select
                  id="mail-compose-item"
                  className="mail-compose-input"
                  value={composeItem}
                  onChange={(e) => setComposeItem(e.target.value)}
                >
                  <option value="">None</option>
                  {inventory.map((it) => (
                    <option key={it.id} value={it.keyword}>{it.name}</option>
                  ))}
                </select>
              </div>
            </div>
            <div className="mail-compose-actions">
              <button className="mail-compose-send" disabled={!canSend} onClick={handleSend}>
                Send
              </button>
              <button className="mail-compose-abort" onClick={resetCompose}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // Inbox list
  return (
    <div className="mail-panel" aria-label="Mail">
      <div className="mail-toolbar">
        <span className="mail-inbox-label">
          Inbox ({inbox.length}){unreadCount > 0 && <span className="mail-unread-badge">{unreadCount} new</span>}
        </span>
        <button className="mail-compose-button" aria-label="Compose new message" onClick={() => setShowCompose(true)}>
          Compose
        </button>
      </div>
      {inbox.length === 0 ? (
        <div className="mail-empty-state">
          <span className="mail-empty-icon" aria-hidden="true">{"\u2709"}</span>
          <p className="empty-note">No messages yet. Your friends can send you mail!</p>
        </div>
      ) : (
        <ul className="mail-inbox-list" role="list">
          {inbox.map((entry) => (
            <li key={entry.id} className={`mail-inbox-item ${entry.read ? "" : "mail-unread"}`}>
              <button className="mail-inbox-row" aria-label={`Read message from ${entry.from}`} onClick={() => onReadMessage(entry.index)}>
                <span className="mail-inbox-marker" aria-hidden="true">{entry.read ? "" : "\u2022"}</span>
                <span className="mail-inbox-from">{entry.from}</span>
                <span className="mail-inbox-preview">{entry.preview}</span>
                {hasUnclaimed(entry) && (
                  <span className="mail-inbox-gift" title="Has an unclaimed gift" aria-label="Has an unclaimed gift">{"\u{1F381}"}</span>
                )}
                <span className="mail-inbox-date">{formatDate(entry.date)}</span>
              </button>
              {confirmDeleteIndex === entry.index ? (
                <button
                  className="mail-delete-button mail-delete-confirm"
                  aria-label={`Confirm delete message from ${entry.from}`}
                  onClick={() => {
                    onDeleteMessage(entry.index);
                    setConfirmDeleteIndex(null);
                  }}
                  onBlur={() => setConfirmDeleteIndex(null)}
                >
                  &#x2713;
                </button>
              ) : (
                <button
                  className="mail-delete-button"
                  aria-label={`Delete message from ${entry.from}`}
                  onClick={() => setConfirmDeleteIndex(entry.index)}
                >
                  &times;
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
