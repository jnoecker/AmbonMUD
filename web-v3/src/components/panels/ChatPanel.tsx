import { useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import { TellIcon } from "../Icons";
import type { ChatChannel, ChatMessage, FriendEntry, FriendNotification, GroupInfo, PendingGroupInvite, SocialTab } from "../../types";

interface ChatPanelProps {
  connected: boolean;
  canChat: boolean;
  playerName: string;
  chatByChannel: Record<ChatChannel, ChatMessage[]>;
  groupInfo: GroupInfo;
  pendingGroupInvite: PendingGroupInvite | null;
  friends: FriendEntry[];
  friendNotifications: FriendNotification[];
  onSendMessage: (channel: ChatChannel, message: string, target: string | null) => boolean;
  onTellPlayer: (target: string) => void;
  onCommand: (command: string) => void;
}

const SOCIAL_TABS: Array<{ id: SocialTab; label: string }> = [
  { id: "friends", label: "Friends" },
  { id: "group", label: "Group" },
];

export function ChatPanel({
  connected,
  canChat,
  playerName,
  chatByChannel,
  groupInfo,
  pendingGroupInvite,
  friends,
  friendNotifications,
  onSendMessage,
  onTellPlayer,
  onCommand,
}: ChatPanelProps) {
  const feedRef = useRef<HTMLDivElement | null>(null);
  const groupFeedRef = useRef<HTMLDivElement | null>(null);
  const [groupDraft, setGroupDraft] = useState("");
  const [activeSocialTab, setActiveSocialTab] = useState<SocialTab>("friends");
  // Group action state
  const [groupInviteTarget, setGroupInviteTarget] = useState("");
  // Friends action state
  const [friendAddTarget, setFriendAddTarget] = useState("");
  const [friendConfirmRemove, setFriendConfirmRemove] = useState<string | null>(null);

  const gtellMessages = chatByChannel.gtell;

  useEffect(() => {
    const feed = feedRef.current;
    if (!feed) return;
    feed.scrollTop = feed.scrollHeight;
  }, [activeSocialTab]);

  useEffect(() => {
    const feed = groupFeedRef.current;
    if (!feed) return;
    feed.scrollTop = feed.scrollHeight;
  }, [activeSocialTab, gtellMessages.length]);

  const handleSocialTabChange = (tab: SocialTab) => {
    setActiveSocialTab(tab);
  };

  const handleTellFromWho = (target: string) => {
    onTellPlayer(target);
  };

  const inGroup = groupInfo.members.length > 0;

  const sortedFriends = useMemo(() => {
    return [...friends].sort((a, b) => {
      const onlineDiff = (a.online ? 0 : 1) - (b.online ? 0 : 1);
      if (onlineDiff !== 0) return onlineDiff;
      return a.name.localeCompare(b.name);
    });
  }, [friends]);

  const [friendsNow, setFriendsNow] = useState(() => Date.now());
  const hasFriendNotifications = friendNotifications.length > 0;

  useEffect(() => {
    if (!hasFriendNotifications || activeSocialTab !== "friends") return;
    const interval = window.setInterval(() => setFriendsNow(Date.now()), 5_000);
    return () => window.clearInterval(interval);
  }, [hasFriendNotifications, activeSocialTab]);

  const recentNotifications = useMemo(() => {
    return friendNotifications.filter((n) => friendsNow - n.receivedAt < 30_000);
  }, [friendNotifications, friendsNow]);

  return (
    <section className="panel panel-chat" aria-label="Social channels">
      <div className="social-tabs" role="tablist" aria-label="Social sections">
        {SOCIAL_TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            className={`social-tab ${activeSocialTab === tab.id ? "social-tab-active" : ""}`}
            onClick={() => handleSocialTabChange(tab.id)}
            aria-selected={activeSocialTab === tab.id}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className="chat-shell">

        {activeSocialTab === "friends" && (
          <>
            <form className="social-action-bar" onSubmit={(e) => { e.preventDefault(); const t = friendAddTarget.trim(); if (t) { onCommand(`friend add ${t}`); setFriendAddTarget(""); } }}>
              <input
                type="text"
                className="social-action-input"
                placeholder="Add friend\u2026"
                value={friendAddTarget}
                onChange={(e) => setFriendAddTarget(e.target.value)}
                aria-label="Friend name to add"
                disabled={!canChat}
              />
              <button type="submit" className="social-action-btn" disabled={!canChat || !friendAddTarget.trim()}>Add</button>
            </form>
            <div ref={feedRef} className="chat-feed" role="region" aria-label="Friends list">
              <section className="chat-feed-panel chat-feed-panel-flip" aria-label="Friends subwindow">
                {!canChat ? (
                  <p className="empty-note">
                    {connected ? "Log in to unlock social features." : "Reconnect to load social data."}
                  </p>
                ) : sortedFriends.length === 0 && recentNotifications.length === 0 ? (
                  <p className="empty-note">No friends yet. Add someone above to get started.</p>
                ) : (
                  <div className="friends-panel-content">
                    {recentNotifications.length > 0 && (
                      <ul className="friend-notifications">
                        {recentNotifications.map((n) => (
                          <li key={n.id} className={`friend-notification friend-notification-${n.event}`}>
                            <span className={`friend-status-dot friend-status-dot-${n.event === "online" ? "online" : "offline"}`} />
                            <span className="friend-notification-text">
                              {n.name} {n.event === "online" ? "came online" : "went offline"}
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                    {sortedFriends.length > 0 && (
                      <ul className="friend-list">
                        {sortedFriends.map((friend) => (
                          <li key={friend.name} className={`friend-item ${friend.online ? "" : "friend-item-offline"}`}>
                            <div className="friend-item-left">
                              <span className={`friend-status-dot friend-status-dot-${friend.online ? "online" : "offline"}`} />
                              <span className="friend-item-name">{friend.name}</span>
                            </div>
                            <div className="friend-item-right">
                              {friend.online && friend.zone && <span className="friend-item-zone">{friend.zone}</span>}
                              {friend.level !== null && <span className="friend-item-level">Lv {friend.level}</span>}
                              {friend.online && (
                                <button
                                  type="button"
                                  className="who-tell-button"
                                  title={`Tell ${friend.name}`}
                                  aria-label={`Tell ${friend.name}`}
                                  onClick={() => handleTellFromWho(friend.name)}
                                >
                                  <TellIcon className="who-tell-icon" />
                                </button>
                              )}
                              {friendConfirmRemove === friend.name ? (
                                <span className="social-confirm-inline">
                                  <button type="button" className="social-confirm-yes" aria-label="Confirm removal" onClick={() => { onCommand(`friend remove ${friend.name}`); setFriendConfirmRemove(null); }}>Remove?</button>
                                  <button type="button" className="social-confirm-no" aria-label="Cancel removal" onClick={() => setFriendConfirmRemove(null)}>&times;</button>
                                </span>
                              ) : (
                                <button
                                  type="button"
                                  className="social-remove-btn"
                                  title={`Remove ${friend.name}`}
                                  aria-label={`Remove ${friend.name}`}
                                  onClick={() => setFriendConfirmRemove(friend.name)}
                                >
                                  &times;
                                </button>
                              )}
                            </div>
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
          !canChat ? (
            <>
              <div aria-hidden="true" />
              <div className="chat-feed" role="region" aria-label="Group members">
                <section className="chat-feed-panel" aria-label="Group subwindow">
                  <p className="empty-note">
                    {connected ? "Log in to unlock social features." : "Reconnect to load social data."}
                  </p>
                </section>
              </div>
              <div aria-hidden="true" />
            </>
          ) : !inGroup ? (
            <>
              <div aria-hidden="true" />
              <div className="chat-feed" role="region" aria-label="Group members">
                <section className="chat-feed-panel" aria-label="Group subwindow">
                  <div className="social-empty-action">
                    <p className="empty-note">You are not in a group.</p>
                    {pendingGroupInvite ? (
                      <div className="invite-card">
                        <p className="invite-card-text"><strong>{pendingGroupInvite.inviterName}</strong> invites you to join their group.</p>
                        <div className="invite-card-actions">
                          <button type="button" className="social-action-btn social-accept-btn" onClick={() => { onCommand("group accept"); }}>Accept</button>
                          <button type="button" className="social-action-btn social-decline-btn" onClick={() => onCommand("group decline")}>Decline</button>
                        </div>
                      </div>
                    ) : (
                      <button type="button" className="social-action-btn social-accept-btn" onClick={() => onCommand("group accept")}>Accept Invite</button>
                    )}
                    <form className="social-action-bar" onSubmit={(e) => { e.preventDefault(); const t = groupInviteTarget.trim(); if (t) { onCommand(`group invite ${t}`); setGroupInviteTarget(""); } }}>
                      <input type="text" className="social-action-input" placeholder="Invite player\u2026" value={groupInviteTarget} onChange={(e) => setGroupInviteTarget(e.target.value)} aria-label="Player to invite" />
                      <button type="submit" className="social-action-btn" disabled={!groupInviteTarget.trim()}>Invite</button>
                    </form>
                  </div>
                </section>
              </div>
              <div aria-hidden="true" />
            </>
          ) : (
            <div className="group-tab-layout">
              <div className="group-tab-members">
                <form className="social-action-bar group-invite-bar" onSubmit={(e) => { e.preventDefault(); const t = groupInviteTarget.trim(); if (t) { onCommand(`group invite ${t}`); setGroupInviteTarget(""); } }}>
                  <input type="text" className="social-action-input" placeholder="Invite player\u2026" value={groupInviteTarget} onChange={(e) => setGroupInviteTarget(e.target.value)} aria-label="Player to invite" disabled={!canChat} />
                  <button type="submit" className="social-action-btn" disabled={!canChat || !groupInviteTarget.trim()}>Invite</button>
                </form>
                <ul className="group-member-list">
                  {groupInfo.members.map((member) => {
                    const isLeader = member.name === groupInfo.leader;
                    const isMe = member.name.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
                    const iAmLeader = playerName.localeCompare(groupInfo.leader ?? "", undefined, { sensitivity: "accent" }) === 0;
                    const hpPct = Math.min(100, (member.hp / Math.max(1, member.maxHp)) * 100);
                    return (
                      <li key={member.name} className="group-member-item">
                        <div className="group-member-header">
                          <span className="group-member-name">
                            {isLeader && <span className="group-leader-badge" title="Leader">&#9733;</span>}
                            {member.name}
                          </span>
                          <span className="group-member-class">
                            {member.playerClass} {member.level}
                            {iAmLeader && !isMe && (
                              <button type="button" className="social-inline-btn social-inline-btn-danger" onClick={() => onCommand(`group kick ${member.name}`)} title={`Kick ${member.name}`}>&times;</button>
                            )}
                          </span>
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
                <div className="group-footer-actions">
                  <button type="button" className="social-action-btn social-danger-btn" onClick={() => onCommand("group leave")}>Leave Group</button>
                </div>
              </div>

              <div ref={groupFeedRef} className="embedded-chat-feed" role="log" aria-live="polite" aria-label="Group chat messages">
                {gtellMessages.length === 0 ? (
                  <p className="empty-note">No group messages yet.</p>
                ) : (
                  <ul className="chat-message-list">
                    {gtellMessages.map((entry) => {
                      const isSelf = entry.sender.localeCompare(playerName, undefined, { sensitivity: "accent" }) === 0;
                      const time = new Date(entry.receivedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
                      return (
                        <li key={entry.id} className={`chat-message-item ${isSelf ? "chat-message-item-self" : ""}`}>
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
              </div>

              <form
                className="chat-form"
                onSubmit={(event: FormEvent<HTMLFormElement>) => {
                  event.preventDefault();
                  const sent = onSendMessage("gtell", groupDraft, null);
                  if (sent) setGroupDraft("");
                }}
              >
                <input
                  className="chat-input"
                  type="text"
                  value={groupDraft}
                  onChange={(event) => setGroupDraft(event.target.value)}
                  placeholder="Message your group"
                  aria-label="Group chat message"
                  autoComplete="off"
                  spellCheck={false}
                  disabled={!canChat}
                />
                <button type="submit" className="soft-button" disabled={!canChat}>Send</button>
              </form>
            </div>
          )
        )}

      </div>
    </section>
  );
}
