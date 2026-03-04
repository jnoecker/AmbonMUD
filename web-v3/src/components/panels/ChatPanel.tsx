import { useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import { CHAT_CHANNELS } from "../../constants";
import { RefreshIcon, TellIcon } from "../Icons";
import type { ChatChannel, ChatMessage, GroupInfo, GuildInfo, GuildMemberEntry, SocialTab } from "../../types";

interface ChatPanelProps {
  connected: boolean;
  canChat: boolean;
  playerName: string;
  activeChannel: ChatChannel;
  messages: ChatMessage[];
  whoPlayers: string[];
  groupInfo: GroupInfo;
  guildInfo: GuildInfo;
  guildMembers: GuildMemberEntry[];
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
    gtell: "",
    gchat: "",
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

const SOCIAL_TABS: Array<{ id: SocialTab; label: string }> = [
  { id: "chat", label: "Chat" },
  { id: "guild", label: "Guild" },
  { id: "group", label: "Group" },
  { id: "who", label: "Who" },
];

export function ChatPanel({
  connected,
  canChat,
  playerName,
  activeChannel,
  messages,
  whoPlayers,
  groupInfo,
  guildInfo,
  guildMembers,
  onChannelChange,
  onRequestWho,
  onSendMessage,
}: ChatPanelProps) {
  const feedRef = useRef<HTMLDivElement | null>(null);
  const messageInputRef = useRef<HTMLInputElement | null>(null);
  const [draftByChannel, setDraftByChannel] = useState<Record<ChatChannel, string>>(createEmptyDrafts);
  const [targets, setTargets] = useState<Record<"tell", string>>(createEmptyTargets);
  const [activeSocialTab, setActiveSocialTab] = useState<SocialTab>("chat");

  useEffect(() => {
    const feed = feedRef.current;
    if (!feed) return;
    feed.scrollTop = feed.scrollHeight;
  }, [activeSocialTab, activeChannel, messages.length, whoPlayers.length]);

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

  const handleSocialTabChange = (tab: SocialTab) => {
    setActiveSocialTab(tab);
    if (tab === "who" && canChat) onRequestWho();
  };

  const handleTellFromWho = (target: string) => {
    setTargets((prev) => ({ ...prev, tell: target }));
    setActiveSocialTab("chat");
    onChannelChange("tell");
    window.requestAnimationFrame(() => messageInputRef.current?.focus());
  };

  const inGroup = groupInfo.members.length > 0;
  const inGuild = guildInfo.name !== null;

  const rankLabel = (rank: string) => {
    switch (rank) {
      case "LEADER": return "Leader";
      case "OFFICER": return "Officer";
      default: return "Member";
    }
  };

  const sortedGuildMembers = useMemo(() => {
    const rankOrder: Record<string, number> = { LEADER: 0, OFFICER: 1, MEMBER: 2 };
    return [...guildMembers].sort((a, b) => {
      const onlineDiff = (a.online ? 0 : 1) - (b.online ? 0 : 1);
      if (onlineDiff !== 0) return onlineDiff;
      const rankDiff = (rankOrder[a.rank] ?? 2) - (rankOrder[b.rank] ?? 2);
      if (rankDiff !== 0) return rankDiff;
      return a.name.localeCompare(b.name);
    });
  }, [guildMembers]);

  return (
    <section className="panel panel-chat" aria-label="Social channels">
      <nav className="social-tabs" aria-label="Social sections">
        {SOCIAL_TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            className={`social-tab ${activeSocialTab === tab.id ? "social-tab-active" : ""}`}
            onClick={() => handleSocialTabChange(tab.id)}
            aria-pressed={activeSocialTab === tab.id}
          >
            {tab.label}
          </button>
        ))}
      </nav>

      <div className="chat-shell">
        {activeSocialTab === "chat" && (
          <>
            <nav className="chat-channel-tabs" aria-label="Chat channels">
              {CHAT_CHANNELS.map((channel) => (
                <button
                  key={channel.id}
                  type="button"
                  className={`chat-channel-tab ${activeChannel === channel.id ? "chat-channel-tab-active" : ""}`}
                  onClick={() => onChannelChange(channel.id)}
                  aria-pressed={activeChannel === channel.id}
                >
                  {channel.label}
                </button>
              ))}
            </nav>

            <div ref={feedRef} className="chat-feed" role="log" aria-live="polite" aria-label={`${activeMeta.label} messages`}>
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
            </div>

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
          </>
        )}

        {activeSocialTab === "guild" && (
          <>
            <div aria-hidden="true" />
            <div ref={feedRef} className="chat-feed" role="region" aria-label="Guild info">
              <section className="chat-feed-panel chat-feed-panel-flip" aria-label="Guild subwindow">
                {!canChat ? (
                  <p className="empty-note">
                    {connected ? "Log in through the terminal to unlock social features." : "Reconnect to load social data."}
                  </p>
                ) : !inGuild ? (
                  <p className="empty-note">You are not in a guild. Use `guild create &lt;name&gt; &lt;tag&gt;` to found one.</p>
                ) : (
                  <div className="guild-panel-content">
                    <div className="guild-info-header">
                      <span className="guild-name">{guildInfo.name}</span>
                      {guildInfo.tag && <span className="guild-tag">[{guildInfo.tag}]</span>}
                    </div>
                    {guildInfo.rank && (
                      <div className="guild-rank">Your rank: {rankLabel(guildInfo.rank)}</div>
                    )}
                    {guildInfo.motd && (
                      <div className="guild-motd">
                        <span className="guild-motd-label">MOTD</span>
                        <p className="guild-motd-text">{guildInfo.motd}</p>
                      </div>
                    )}
                    <div className="guild-roster-header">
                      Roster ({guildInfo.memberCount} / {guildInfo.maxSize})
                    </div>
                    {sortedGuildMembers.length === 0 ? (
                      <p className="empty-note">No roster data yet.</p>
                    ) : (
                      <ul className="guild-member-list">
                        {sortedGuildMembers.map((member) => (
                          <li key={member.name} className={`guild-member-item ${member.online ? "" : "guild-member-offline"}`}>
                            <span className="guild-member-name">{member.name}</span>
                            <span className="guild-member-details">
                              <span className={`guild-member-status ${member.online ? "guild-member-status-online" : "guild-member-status-offline"}`} />
                              <span className="guild-member-rank">{rankLabel(member.rank)}</span>
                              {member.level !== null && <span className="guild-member-level">Lv {member.level}</span>}
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
              </section>
            </div>
            <div aria-hidden="true" />
          </>
        )}

        {activeSocialTab === "group" && (
          <>
            <div aria-hidden="true" />
            <div ref={feedRef} className="chat-feed" role="region" aria-label="Group members">
              <section className="chat-feed-panel chat-feed-panel-flip" aria-label="Group subwindow">
                {!canChat ? (
                  <p className="empty-note">
                    {connected ? "Log in through the terminal to unlock social features." : "Reconnect to load social data."}
                  </p>
                ) : !inGroup ? (
                  <p className="empty-note">You are not in a group. Use `group invite &lt;name&gt;` to start one.</p>
                ) : (
                  <ul className="group-member-list">
                    {groupInfo.members.map((member) => {
                      const isLeader = member.name === groupInfo.leader;
                      const hpPct = Math.min(100, (member.hp / Math.max(1, member.maxHp)) * 100);
                      return (
                        <li key={member.name} className="group-member-item">
                          <div className="group-member-header">
                            <span className="group-member-name">
                              {isLeader && <span className="group-leader-badge" title="Leader">&#9733;</span>}
                              {member.name}
                            </span>
                            <span className="group-member-class">{member.playerClass} {member.level}</span>
                          </div>
                          <div className="meter-track group-member-hp-track">
                            <span
                              className="meter-fill meter-fill-hp"
                              style={{ width: `${hpPct}%` }}
                            />
                          </div>
                          <div className="group-member-hp-text">{member.hp} / {member.maxHp}</div>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </section>
            </div>
            <div aria-hidden="true" />
          </>
        )}


        {activeSocialTab === "who" && (
          <>
            <div aria-hidden="true" />
            <div ref={feedRef} className="chat-feed" role="log" aria-label="Who player list">
              <section className="chat-feed-panel chat-feed-panel-flip" aria-label="Who subwindow">
                {!canChat ? (
                  <p className="empty-note">
                    {connected ? "Log in through the terminal to unlock social features." : "Reconnect to load social data."}
                  </p>
                ) : whoPlayers.length === 0 ? (
                  <p className="empty-note">No player list yet. Switch to this tab again or use refresh to request `who`.</p>
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
                            onClick={() => handleTellFromWho(target)}
                          >
                            <TellIcon className="who-tell-icon" />
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </section>
            </div>

            <div className="chat-form chat-form-who">
              <button type="button" className="soft-button who-refresh-button" onClick={onRequestWho} disabled={!canChat}>
                <RefreshIcon className="who-refresh-icon" />
                Refresh
              </button>
            </div>
          </>

        )}
      </div>
    </section>
  );
}
