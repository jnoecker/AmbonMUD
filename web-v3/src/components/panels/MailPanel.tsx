import { useState } from "react";
import type { MailEntry, MailMessage } from "../../types";

interface MailPanelProps {
  connected: boolean;
  hasCharacterProfile: boolean;
  inbox: MailEntry[];
  openMessage: MailMessage | null;
  onReadMessage: (index: number) => void;
  onDeleteMessage: (index: number) => void;
  onCompose: (recipient: string) => void;
  onClearMessage: () => void;
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
}: MailPanelProps) {
  const [composeTarget, setComposeTarget] = useState("");
  const [showCompose, setShowCompose] = useState(false);

  if (!connected) {
    return <p className="empty-note">Connect to view mail.</p>;
  }
  if (!hasCharacterProfile) {
    return <p className="empty-note">Log in to view mail.</p>;
  }

  const unreadCount = inbox.filter((m) => !m.read).length;

  // Reading a message
  if (openMessage) {
    return (
      <div className="mail-panel">
        <div className="mail-message-view">
          <div className="mail-message-header">
            <button className="mail-back-button" onClick={onClearMessage}>
              &larr; Back
            </button>
            <span className="mail-message-title">Message {openMessage.index}</span>
          </div>
          <div className="mail-message-meta">
            <span className="mail-message-from">From: <strong>{openMessage.from}</strong></span>
            <span className="mail-message-date">{formatDate(openMessage.date)}</span>
          </div>
          <pre className="mail-message-body">{openMessage.body}</pre>
        </div>
      </div>
    );
  }

  // Compose form
  if (showCompose) {
    return (
      <div className="mail-panel">
        <div className="mail-compose">
          <div className="mail-message-header">
            <button className="mail-back-button" onClick={() => { setShowCompose(false); setComposeTarget(""); }}>
              &larr; Back
            </button>
            <span className="mail-message-title">New Message</span>
          </div>
          <div className="mail-compose-form">
            <input
              type="text"
              className="mail-compose-input"
              placeholder="Recipient name"
              value={composeTarget}
              onChange={(e) => setComposeTarget(e.target.value)}
              autoFocus
            />
            <button
              className="mail-compose-send"
              disabled={composeTarget.trim().length === 0}
              onClick={() => {
                onCompose(composeTarget.trim());
                setComposeTarget("");
                setShowCompose(false);
              }}
            >
              Begin Compose
            </button>
            <p className="mail-compose-hint">
              This will start a compose session in the terminal. Type your message line by line, then type <code>.</code> alone to send.
            </p>
          </div>
        </div>
      </div>
    );
  }

  // Inbox list
  return (
    <div className="mail-panel">
      <div className="mail-toolbar">
        <span className="mail-inbox-label">
          Inbox ({inbox.length}){unreadCount > 0 && <span className="mail-unread-badge">{unreadCount} new</span>}
        </span>
        <button className="mail-compose-button" onClick={() => setShowCompose(true)}>
          Compose
        </button>
      </div>
      {inbox.length === 0 ? (
        <p className="empty-note">Your inbox is empty.</p>
      ) : (
        <ul className="mail-inbox-list">
          {inbox.map((entry) => (
            <li key={entry.id} className={`mail-inbox-item ${entry.read ? "" : "mail-unread"}`}>
              <button className="mail-inbox-row" onClick={() => onReadMessage(entry.index)}>
                <span className="mail-inbox-marker">{entry.read ? "" : "\u2022"}</span>
                <span className="mail-inbox-from">{entry.from}</span>
                <span className="mail-inbox-preview">{entry.preview}</span>
                <span className="mail-inbox-date">{formatDate(entry.date)}</span>
              </button>
              <button
                className="mail-delete-button"
                title="Delete message"
                onClick={() => onDeleteMessage(entry.index)}
              >
                &times;
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
