import { useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import { CHAT_CHANNELS } from "../../constants";
import { TellIcon } from "../Icons";
import type { ChatChannel, ChatMessage } from "../../types";

type SocialSubTab = ChatChannel | "who";

interface ChatPanelProps {
  connected: boolean;
  canChat: boolean;
  playerName: string;
  activeChannel: ChatChannel;
  messages: ChatMessage[];
  whoPlayers: string[];
  onChannelChange: (channel: ChatChannel) => void;
  onRequestWho: () => void;
  onSendMessage: (channel: ChatChannel, message: string, target: string | null) => boolean;
}

function createEmptyDrafts(): Record<ChatChannel, string> {
  return {
    say: "",
    tell: "",
    gossip: "",
    shout: "",
    ooc: "",
  };
}

function createEmptyTargets(): Record<"tell", string> {
  return {
    tell: "",
  };
}

function extractTellTargetFromWhoEntry(entry: string): string {
  const stripped = entry.replace(/^\s*(\[[^\]]+\]\s*)+/g, "").trim();
  const name = stripped.split(/\s+/)[0];
  return name || entry.trim();
}

export function ChatPanel({
  connected,
  canChat,
  playerName,
  activeChannel,
  messages,
  whoPlayers,
  onChannelChange,
  onRequestWho,
  onSendMessage,
}: ChatPanelProps) {
  const feedRef = useRef<HTMLDivElement | null>(null);
  const messageInputRef = useRef<HTMLInputElement | null>(null);
  const [draftByChannel, setDraftByChannel] = useState<Record<ChatChannel, string>>(createEmptyDrafts);
  const [targets, setTargets] = useState<Record<"tell", string>>(createEmptyTargets);
  const [activeSocialTab, setActiveSocialTab] = useState<SocialSubTab>(activeChannel);

  useEffect(() => {
    const feed = feedRef.current;
    if (!feed) return;
    feed.scrollTop = feed.scrollHeight;
  }, [activeSocialTab, messages.length, whoPlayers.length]);

  const activeMeta = useMemo(
    () => CHAT_CHANNELS.find((channel) => channel.id === activeChannel) ?? CHAT_CHANNELS[0],
    [activeChannel],
  );
  const draft = draftByChannel[activeChannel];
  const isTargetedChannel = activeMeta.requiresTarget;
  const targetValue = activeChannel === "tell" ? targets.tell : "";

  const submitMessage = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const target = activeMeta.requiresTarget ? targetValue : null;
    const sent = onSendMessage(activeChannel, draft, target);
    if (!sent) return;
    setDraftByChannel((prev) => ({ ...prev, [activeChannel]: "" }));
  };

  return (
    <section className="panel panel-chat" aria-label="Social channels">
      <header className="panel-header"><h2 title="Channel-based communication feed.">Social</h2></header>

      <div className="chat-shell">
        <nav className="chat-channel-tabs" aria-label="Social channels">
          {CHAT_CHANNELS.map((channel) => (
            <button
              key={channel.id}
              type="button"
              className={`chat-channel-tab ${activeSocialTab === channel.id ? "chat-channel-tab-active" : ""}`}
              onClick={() => {
                setActiveSocialTab(channel.id);
                onChannelChange(channel.id);
              }}
              aria-pressed={activeSocialTab === channel.id}
            >
              {channel.label}
            </button>
          ))}
          <button
            type="button"
            className={`chat-channel-tab ${activeSocialTab === "who" ? "chat-channel-tab-active" : ""}`}
            onClick={() => {
              setActiveSocialTab("who");
              if (canChat) onRequestWho();
            }}
            aria-pressed={activeSocialTab === "who"}
          >
            Who
          </button>
        </nav>

        <div ref={feedRef} className="chat-feed" role="log" aria-live="polite" aria-label={`${activeMeta.label} messages`}>
          {activeSocialTab === "who" ? (
            <section key="who" className="chat-feed-panel chat-feed-panel-flip" aria-label="Who subwindow">
              {!canChat ? (
                <p className="empty-note">
                  {connected ? "Log in through the terminal to unlock social features." : "Reconnect to load social data."}
                </p>
              ) : whoPlayers.length === 0 ? (
                <p className="empty-note">No player list yet. Open this tab again or use refresh to request `who`.</p>
              ) : (
                <ul className="who-player-list">
                  {whoPlayers.map((entry, index) => {
                    const target = extractTellTargetFromWhoEntry(entry);
                    return (
                      <li key={`${target}-${index}`} className="who-player-item">
                        <span className="who-player-name">{entry}</span>
                        <button
                          type="button"
                          className="who-tell-button"
                          title={`Tell ${target}`}
                          aria-label={`Tell ${target}`}
                          onClick={() => {
                            setTargets((prev) => ({ ...prev, tell: target }));
                            setActiveSocialTab("tell");
                            onChannelChange("tell");
                            window.requestAnimationFrame(() => messageInputRef.current?.focus());
                          }}
                        >
                          <TellIcon className="who-tell-icon" />
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>
          ) : (
            <section key={activeChannel} className="chat-feed-panel chat-feed-panel-flip" aria-label={`${activeMeta.label} subwindow`}>
              {messages.length === 0 ? (
                <p className="empty-note">
                  {canChat
                    ? `No ${activeMeta.label.toLowerCase()} messages yet.`
                    : connected
                      ? "Log in through the terminal to unlock chat."
                      : "Reconnect to resume channel chat."}
                </p>
              ) : (
                <ul className="chat-message-list">
                  {messages.map((entry) => {
                    const isSelf = entry.sender.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
                    const time = new Date(entry.receivedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
                    return (
                      <li
                        key={entry.id}
                        className={`chat-message-item ${isSelf ? "chat-message-item-self" : ""}`}
                      >
                        <div className="chat-message-meta">
                          <span className="chat-message-sender">{isSelf ? "You" : entry.sender}</span>
                          <span className="chat-message-time">{time}</span>
                        </div>
                        <p className="chat-message-body">{entry.message}</p>
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>
          )}
        </div>

        {activeSocialTab === "who" ? (
          <div className="chat-form chat-form-who">
            <button type="button" className="soft-button" onClick={onRequestWho} disabled={!canChat}>Refresh Who</button>
          </div>
        ) : (
          <form
            className={`chat-form ${isTargetedChannel ? "chat-form-targeted" : ""}`}
            onSubmit={submitMessage}
          >
            {isTargetedChannel && (
              <input
                className="chat-target-input"
                type="text"
                value={targetValue}
                onChange={(event) => {
                  const next = event.target.value;
                  if (activeChannel !== "tell") return;
                  setTargets((prev) => ({ ...prev, tell: next }));
                }}
                placeholder={activeMeta.targetPlaceholder ?? "Target"}
                aria-label={`${activeMeta.label} target`}
                autoComplete="off"
                spellCheck={false}
              />
            )}
            <input
              ref={messageInputRef}
              className="chat-input"
              type="text"
              value={draft}
              onChange={(event) => setDraftByChannel((prev) => ({ ...prev, [activeChannel]: event.target.value }))}
              placeholder={canChat ? activeMeta.messagePlaceholder : "Chat unavailable"}
              aria-label={`${activeMeta.label} message`}
              autoComplete="off"
              spellCheck={false}
              disabled={!canChat}
            />
            <button type="submit" className="soft-button" disabled={!canChat}>Send</button>
          </form>
        )}
      </div>
    </section>
  );
}
