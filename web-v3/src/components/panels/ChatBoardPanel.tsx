import { useEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties, FormEvent } from "react";
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
  backgroundImage: string | null;
  onTellTargetChange: (value: string) => void;
  onChannelChange: (channel: ChatChannel) => void;
  onSendMessage: (channel: ChatChannel, message: string, target: string | null) => boolean;
  onCommand: (command: string) => void;
}

type Rect = { left: number; top: number; width: number; height: number };

/**
 * Hotspot rectangles, expressed as percentages of the painted `chat_bg`
 * frame (1447×1087, 4:3). The labels/icons/buttons are baked into the art;
 * these transparent overlays sit on top to make the chrome interactive.
 * Tweak these to nudge the lineup against the painted frame.
 */
const CHANNEL_RECTS: Record<string, Rect> = {
  say: { left: 17.5, top: 25.5, width: 11.2, height: 7.5 },
  tell: { left: 30.5, top: 25.5, width: 11.2, height: 7.5 },
  gossip: { left: 43.5, top: 25.5, width: 11.2, height: 7.5 },
  shout: { left: 56.5, top: 25.5, width: 11.2, height: 7.5 },
  ooc: { left: 69.5, top: 25.5, width: 11.2, height: 7.5 },
};
// Measured off social-final.png (1448×1086): chalkboard l15.9 t34.3 w69.3 h44.7;
// parchment l21.3 t82.0 w46.3 h13.4; send-scroll l~77 w~17.
const FEED_RECT: Rect = { left: 16.0, top: 34.5, width: 69.0, height: 44.5 };
const INPUT_RECT: Rect = { left: 20.5, top: 81.8, width: 47.0, height: 13.6 };
const EMOTE_RECT: Rect = { left: 70.5, top: 80.0, width: 5.2, height: 6.0 };
const CLEAR_RECT: Rect = { left: 69.8, top: 87.0, width: 6.6, height: 6.8 };
const SEND_RECT: Rect = { left: 77.0, top: 80.5, width: 16.8, height: 13.5 };

// Global nudge applied to every hotspot — corrects a uniform offset between
// where the painted frame renders and the hotspot coordinate box. Positive X
// moves right, negative Y moves up. Tune these two to shift everything at once.
const OFFSET_X = 2.5;
const OFFSET_Y = -3.0;

const CHANNEL_ACCENTS: Record<string, string> = {
  say: "var(--chat-accent-say)",
  tell: "var(--chat-accent-tell)",
  gossip: "var(--chat-accent-gossip)",
  shout: "var(--chat-accent-shout)",
  ooc: "var(--chat-accent-ooc)",
};

function rectStyle(r: Rect): CSSProperties {
  return {
    left: `${r.left + OFFSET_X}%`,
    top: `${r.top + OFFSET_Y}%`,
    width: `${r.width}%`,
    height: `${r.height}%`,
  };
}

function createEmptyDrafts(): Record<ChatChannel, string> {
  return { say: "", tell: "", gossip: "", shout: "", ooc: "", gtell: "", gchat: "" };
}

/**
 * Standalone "Social Board" — chat split out of the larger Social panel.
 * The entire frame (banner, channel plaques, side notes, parchment input,
 * Send/Clear buttons, footer) is painted into the `chat_bg` art; this
 * component overlays transparent hotspots + the live feed and input text on
 * top, pixel-mapped to the painted controls.
 */
export function ChatBoardPanel({
  connected,
  canChat,
  playerName,
  activeChannel,
  chatByChannel,
  emotePresets,
  tellTarget,
  backgroundImage,
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

  // When the "tell" channel is active, split the painted input field into a
  // target box (left third) and a message box (right two-thirds).
  const targetRect: Rect = { ...INPUT_RECT, width: INPUT_RECT.width * 0.32 };
  const messageRect: Rect = isTargetedChannel
    ? {
      ...INPUT_RECT,
      left: INPUT_RECT.left + INPUT_RECT.width * 0.35,
      width: INPUT_RECT.width * 0.65,
    }
    : INPUT_RECT;

  const boardStyle: CSSProperties = backgroundImage
    ? { ["--chat-bg" as string]: `url("${backgroundImage}")` }
    : {};

  return (
    <div className="chatboard" style={boardStyle}>
      {/* ── Channel hotspots (labels painted into the art) ────── */}
      {CHAT_CHANNELS.map((channel) => {
        const isActive = activeChannel === channel.id;
        return (
          <button
            key={channel.id}
            type="button"
            role="tab"
            className={`cb-hotspot cb-channel${isActive ? " is-active" : ""}`}
            style={{ ...rectStyle(CHANNEL_RECTS[channel.id] ?? FEED_RECT), ["--chat-accent" as string]: CHANNEL_ACCENTS[channel.id] ?? "var(--chat-accent-say)" }}
            onClick={() => onChannelChange(channel.id)}
            aria-selected={isActive}
            aria-label={channel.label}
            title={channel.label}
          />
        );
      })}

      {/* ── Message feed (over the painted chalkboard) ────────── */}
      <div
        ref={feedRef}
        className="cb-feed"
        style={rectStyle(FEED_RECT)}
        role="log"
        aria-live="polite"
        aria-label={`${activeMeta.label} messages`}
      >
        {messages.length === 0 ? (
          <p className="cb-empty">
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

      {/* ── Emote picker (floats over the feed when open) ─────── */}
      {emotePickerOpen && canChat && (
        <div className="cb-emote-picker" style={rectStyle({ ...FEED_RECT, top: FEED_RECT.top + FEED_RECT.height - 22, height: 22 })}>
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
            <button type="submit" className="social-action-btn" disabled={!customEmote.trim()}>Emote</button>
          </form>
        </div>
      )}

      {/* ── Composer (over the painted parchment + buttons) ───── */}
      <form className="cb-compose" onSubmit={submitMessage}>
        {isTargetedChannel && (
          <input
            className="cb-input cb-target"
            style={rectStyle(targetRect)}
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
          className="cb-input"
          style={rectStyle(messageRect)}
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
          className={`cb-hotspot cb-emote${emotePickerOpen ? " is-active" : ""}`}
          style={rectStyle(EMOTE_RECT)}
          title="Emotes"
          aria-label="Toggle emote picker"
          aria-expanded={emotePickerOpen}
          onClick={() => setEmotePickerOpen((v) => !v)}
          disabled={!canChat}
        />
        <button
          type="button"
          className="cb-hotspot cb-clear"
          style={rectStyle(CLEAR_RECT)}
          title="Clear message"
          aria-label="Clear message"
          onClick={() => setDraftByChannel((prev) => ({ ...prev, [activeChannel]: "" }))}
          disabled={!draft}
        />
        <button
          type="submit"
          className="cb-hotspot cb-send"
          style={rectStyle(SEND_RECT)}
          title="Send message"
          aria-label="Send message"
          disabled={!canChat}
        />
      </form>
    </div>
  );
}
