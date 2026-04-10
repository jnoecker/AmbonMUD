import { useEffect, useRef, useState } from "react";
import type { MailEntry, MailMessage } from "../../types";

interface MailPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  inbox: MailEntry[] | null;
  openMessage: MailMessage | null;
  onReadMessage: (index: number) => void;
  onDeleteMessage: (index: number) => void;
  onCompose: (recipient: string, body: string) => void;
  onClearMessage: () => void;
  onCommand: (cmd: string) => void;
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
  onReadMessage,
  onDeleteMessage,
  onCompose,
  onClearMessage,
  onCommand,
}: MailPanelProps) {
  const [composeTarget, setComposeTarget] = useState("");
  const [composeBody, setComposeBody] = useState("");
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
        </div>
      </div>
    );
  }

  // Compose form
  if (showCompose) {
    const canSend = composeTarget.trim().length > 0 && composeBody.trim().length > 0;

    const handleSend = () => {
      if (!canSend) return;
      onCompose(composeTarget.trim(), composeBody);
      setComposeTarget("");
      setComposeBody("");
      setShowCompose(false);
    };

    return (
      <div className="mail-panel" aria-label="Mail">
        <div className="mail-compose">
          <div className="mail-message-header">
            <button
              className="mail-back-button"
              aria-label="Back to inbox"
              onClick={() => { setShowCompose(false); setComposeTarget(""); setComposeBody(""); }}
            >
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
            <div className="mail-compose-actions">
              <button
                className="mail-compose-send"
                disabled={!canSend}
                onClick={handleSend}
              >
                Send
              </button>
              <button
                className="mail-compose-abort"
                onClick={() => { setShowCompose(false); setComposeTarget(""); setComposeBody(""); }}
              >
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
