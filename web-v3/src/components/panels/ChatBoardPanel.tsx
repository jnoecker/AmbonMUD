import { useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import { CHAT_CHANNELS } from "../../constants";
import type { ChatChannel, ChatMessage, EmotePreset } from "../../types";

interface ChatBoardPanelProps {
  connected: boolean;
  canChat: boolean;
  playerName: string;
  activeChannel: ChatChannel;
  chatByChannel: Record<ChatChannel, ChatMessage[]>;
  emotePresets: EmotePreset[];
  tellTarget: string;
  onTellTargetChange: (value: string) => void;
  onChannelChange: (channel: ChatChannel) => void;
  onSendMessage: (channel: ChatChannel, message: string, target: string | null) => boolean;
  onCommand: (command: string) => void;
}

/** Per-channel flavour for the painted plaques: a sub-label and accent colour. */
const CHANNEL_META: Record<string, { sub: string; accent: string }> = {
  say: { sub: "Local Room", accent: "var(--chat-accent-say)" },
  tell: { sub: "Private", accent: "var(--chat-accent-tell)" },
  gossip: { sub: "Server-Wide", accent: "var(--chat-accent-gossip)" },
  shout: { sub: "Zone-Wide", accent: "var(--chat-accent-shout)" },
  ooc: { sub: "Out of Character", accent: "var(--chat-accent-ooc)" },
};

function createEmptyDrafts(): Record<ChatChannel, string> {
  return { say: "", tell: "", gossip: "", shout: "", ooc: "", gtell: "", gchat: "" };
}

/**
 * Standalone, fully-painted "Social Board" — the chat channels split out of the
 * larger Social panel. The board frame comes from the `chat_bg` drawer skin;
 * this component lays the channel rail, the chalkboard feed, and the parchment
 * composer on top, with carved-CSS fallbacks so it works before the art ships.
 */
export function ChatBoardPanel({
  connected,
  canChat,
  playerName,
  activeChannel,
  chatByChannel,
  emotePresets,
  tellTarget,
  onTellTargetChange,
  onChannelChange,
  onSendMessage,
  onCommand,
}: ChatBoardPanelProps) {
  const feedRef = useRef<HTMLDivElement | null>(null);
  const messageInputRef = useRef<HTMLInputElement | null>(null);
  const [draftByChannel, setDraftByChannel] = useState<Record<ChatChannel, string>>(createEmptyDrafts);
  const [emotePickerOpen, setEmotePickerOpen] = useState(false);
  const [customEmote, setCustomEmote] = useState("");

  const messages = chatByChannel[activeChannel];
  const activeMeta = useMemo(
    () => CHAT_CHANNELS.find((channel) => channel.id === activeChannel) ?? CHAT_CHANNELS[0],
    [activeChannel],
  );
  const draft = draftByChannel[activeChannel];
  const isTargetedChannel = activeMeta.requiresTarget;

  useEffect(() => {
    const feed = feedRef.current;
    if (!feed) return;
    feed.scrollTop = feed.scrollHeight;
  }, [activeChannel, messages.length]);

  const submitMessage = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const target = activeMeta.requiresTarget ? tellTarget : null;
    const sent = onSendMessage(activeChannel, draft, target);
    if (!sent) return;
    setDraftByChannel((prev) => ({ ...prev, [activeChannel]: "" }));
  };

  return (
    <section className="chatboard" aria-label="Social board">
      <div className="chatboard-channels" role="tablist" aria-label="Chat channels">
        {CHAT_CHANNELS.map((channel) => {
          const meta = CHANNEL_META[channel.id];
          const isActive = activeChannel === channel.id;
          return (
            <button
              key={channel.id}
              type="button"
              role="tab"
              className={`chatboard-channel${isActive ? " is-active" : ""}`}
              style={{ ["--chat-accent" as string]: meta?.accent ?? "var(--brass)" }}
              onClick={() => onChannelChange(channel.id)}
              aria-selected={isActive}
            >
              <span className="chatboard-channel-gem" aria-hidden="true" />
              <span className="chatboard-channel-label">{channel.label}</span>
              {meta && <span className="chatboard-channel-sub">{meta.sub}</span>}
            </button>
          );
        })}
      </div>

      <div
        ref={feedRef}
        className="chatboard-feed"
        role="log"
        aria-live="polite"
        aria-label={`${activeMeta.label} messages`}
      >
        {messages.length === 0 ? (
          <p className="chatboard-empty">
            {canChat
              ? `No ${activeMeta.label.toLowerCase()} messages yet.`
              : connected
                ? "Log in to unlock chat."
                : "Reconnect to resume chat."}
          </p>
        ) : (
          <ul className="chat-message-list">
            {messages.map((entry) => {
              const isSelf = entry.sender.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
              const time = new Date(entry.receivedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
              return (
                <li
                  key={entry.id}
                  className={`chat-message-item ${isSelf ? "chat-message-item-self" : ""}${entry.isWhisper ? " chat-message-item-whisper" : ""}`}
                >
                  <div className="chat-message-meta">
                    <span className="chat-message-sender">
                      {entry.isWhisper && <span className="chat-whisper-tag">whisper</span>}
                      {isSelf ? "You" : entry.sender}
                    </span>
                    <span className="chat-message-time">{time}</span>
                  </div>
                  <p className="chat-message-body">{entry.message}</p>
                </li>
              );
            })}
          </ul>
        )}
      </div>

      {emotePickerOpen && canChat && (
        <div className="emote-picker">
          <div className="emote-presets">
            {emotePresets.map((preset) => (
              <button
                key={preset.label}
                type="button"
                className="emote-preset-btn"
                title={`${playerName} ${preset.action}`}
                aria-label={preset.label}
                onClick={() => { onCommand(`emote ${preset.action}`); }}
              >
                <span className="emote-preset-emoji">{preset.emoji}</span>
                <span className="emote-preset-label">{preset.label}</span>
              </button>
            ))}
          </div>
          <form className="emote-custom-form" onSubmit={(e) => {
            e.preventDefault();
            const msg = customEmote.trim();
            if (!msg) return;
            const cmd = msg.toLowerCase().includes(playerName.toLowerCase()) ? "pose" : "emote";
            onCommand(`${cmd} ${msg}`);
            setCustomEmote("");
          }}>
            <input
              type="text"
              className="social-action-input"
              placeholder={`${playerName} does what…`}
              value={customEmote}
              onChange={(e) => setCustomEmote(e.target.value)}
              aria-label="Custom emote"
            />
            <button
              type="button"
              className="emote-name-btn"
              title="Insert your name for freeform pose"
              aria-label="Insert your character name"
              onClick={() => setCustomEmote((prev) => prev + playerName + " ")}
            >
              @me
            </button>
            <button type="submit" className="social-action-btn" disabled={!customEmote.trim()}>Emote</button>
          </form>
        </div>
      )}

      <form
        className={`chatboard-compose${isTargetedChannel ? " is-targeted" : ""}`}
        onSubmit={submitMessage}
      >
        {isTargetedChannel && (
          <input
            className="chatboard-target"
            type="text"
            value={tellTarget}
            onChange={(event) => onTellTargetChange(event.target.value)}
            placeholder={activeMeta.targetPlaceholder ?? "Target"}
            aria-label={`${activeMeta.label} target`}
            autoComplete="off"
            spellCheck={false}
          />
        )}
        <input
          ref={messageInputRef}
          className="chatboard-input"
          type="text"
          value={draft}
          onChange={(event) => setDraftByChannel((prev) => ({ ...prev, [activeChannel]: event.target.value }))}
          placeholder={canChat ? "Type your message…" : "Chat unavailable"}
          aria-label={`${activeMeta.label} message`}
          autoComplete="off"
          spellCheck={false}
          disabled={!canChat}
        />
        <button
          type="button"
          className={`chatboard-emote-btn${emotePickerOpen ? " is-active" : ""}`}
          title="Emotes"
          aria-label="Toggle emote picker"
          aria-expanded={emotePickerOpen}
          onClick={() => setEmotePickerOpen((v) => !v)}
          disabled={!canChat}
        >
          &#9786;
        </button>
        <button
          type="button"
          className="chatboard-clear-btn"
          title="Clear message"
          aria-label="Clear message"
          onClick={() => setDraftByChannel((prev) => ({ ...prev, [activeChannel]: "" }))}
          disabled={!draft}
        >
          Clear
        </button>
        <button type="submit" className="chatboard-send-btn" disabled={!canChat}>
          Send<span className="chatboard-send-btn-line">Message</span>
        </button>
      </form>
    </section>
  );
}
